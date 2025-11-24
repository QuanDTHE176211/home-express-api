package com.homeexpress.home_express_api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.request.RateCardRequest;
import com.homeexpress.home_express_api.dto.response.RateCardResponse;
import com.homeexpress.home_express_api.dto.response.ReadyToQuoteStatusResponse;
import com.homeexpress.home_express_api.entity.Category;
import com.homeexpress.home_express_api.entity.Notification;
import com.homeexpress.home_express_api.entity.RateCard;
import com.homeexpress.home_express_api.entity.RateCardSnapshot;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.repository.CategoryRepository;
import com.homeexpress.home_express_api.repository.RateCardRepository;
import com.homeexpress.home_express_api.repository.RateCardSnapshotRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class RateCardService {

    private final RateCardRepository rateCardRepository;
    private final TransportRepository transportRepository;
    private final CategoryRepository categoryRepository;
    private final RateCardSnapshotRepository rateCardSnapshotRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public RateCardService(RateCardRepository rateCardRepository,
                           TransportRepository transportRepository,
                           CategoryRepository categoryRepository,
                           RateCardSnapshotRepository rateCardSnapshotRepository,
                           NotificationService notificationService,
                           ObjectMapper objectMapper) {
        this.rateCardRepository = rateCardRepository;
        this.transportRepository = transportRepository;
        this.categoryRepository = categoryRepository;
        this.rateCardSnapshotRepository = rateCardSnapshotRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RateCardResponse createOrUpdateRateCard(Long transportId, RateCardRequest request) {
        transportRepository.findById(transportId)
                .orElseThrow(() -> new RuntimeException("Transport not found"));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found"));

        if (request.getValidUntil().isBefore(request.getValidFrom())) {
            throw new RuntimeException("valid_until must be after valid_from");
        }

        LocalDateTime now = LocalDateTime.now();
        if (request.getValidUntil().isBefore(now)) {
            throw new RuntimeException("Rate card expiry must be in the future");
        }

        // deactivate any existing active cards for this category
        List<RateCard> activeForCategory = rateCardRepository
                .findByTransportIdAndCategoryIdAndIsActiveTrue(transportId, request.getCategoryId());
        for (RateCard existing : activeForCategory) {
            existing.setIsActive(false);
            rateCardRepository.save(existing);
        }

        RateCard rateCard = new RateCard();
        rateCard.setTransportId(transportId);
        rateCard.setCategoryId(request.getCategoryId());
        rateCard.setBasePrice(request.getBasePrice());
        rateCard.setPricePerKm(request.getPricePerKm());
        rateCard.setPricePerHour(request.getPricePerHour());
        rateCard.setMinimumCharge(request.getMinimumCharge());
        rateCard.setValidFrom(request.getValidFrom());
        rateCard.setValidUntil(request.getValidUntil());
        rateCard.setIsActive(true);

        Map<String, ?> additionalRules = request.getAdditionalRules();
        if (additionalRules != null && !additionalRules.isEmpty()) {
            try {
                rateCard.setAdditionalRules(objectMapper.writeValueAsString(additionalRules));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize additional rules", e);
            }
        }

        RateCard saved = rateCardRepository.save(rateCard);

        // update READY_TO_QUOTE status based on current cards
        updateReadyToQuoteStatus(transportId);

        Map<String, java.math.BigDecimal> rulesForResponse = request.getAdditionalRules();
        return RateCardResponse.fromEntity(saved, category.getName(), rulesForResponse);
    }

    @Transactional(readOnly = true)
    public List<RateCardResponse> getRateCardsForTransport(Long transportId) {
        List<RateCard> cards = rateCardRepository.findByTransportId(transportId);
        Map<Long, String> categoryNames = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getCategoryId, Category::getName));

        return cards.stream()
                .map(card -> RateCardResponse.fromEntity(
                        card,
                        categoryNames.get(card.getCategoryId()),
                        parseAdditionalRules(card.getAdditionalRules())))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteRateCard(Long transportId, Long rateCardId) {
        RateCard card = rateCardRepository.findById(rateCardId)
                .orElseThrow(() -> new RuntimeException("Rate card not found"));

        if (!Objects.equals(card.getTransportId(), transportId)) {
            throw new RuntimeException("Cannot delete rate card of another transport");
        }

        card.setIsActive(false);
        rateCardRepository.save(card);

        updateReadyToQuoteStatus(transportId);
    }

    @Transactional
    public void checkExpiry(Long transportId) {
        List<RateCard> cards = rateCardRepository.findByTransportId(transportId);
        LocalDateTime now = LocalDateTime.now();

        int newlyExpired = 0;
        for (RateCard card : cards) {
            if (Boolean.TRUE.equals(card.getIsActive())
                    && card.getValidUntil() != null
                    && card.getValidUntil().isBefore(now)) {
                card.setIsActive(false);
                rateCardRepository.save(card);
                newlyExpired++;
            }
        }

        updateReadyToQuoteStatus(transportId);

        if (newlyExpired > 0) {
            Transport transport = transportRepository.findById(transportId)
                    .orElseThrow(() -> new RuntimeException("Transport not found"));

            notificationService.createNotification(
                    transport.getUser().getUserId(),
                    Notification.NotificationType.SYSTEM_ALERT,
                    "Rate card expired",
                    "One or more of your rate cards have expired. Please review your pricing to continue receiving jobs.",
                    Notification.ReferenceType.TRANSPORT,
                    transportId,
                    Notification.Priority.HIGH
            );
        }
    }

    @Transactional
    public ReadyToQuoteStatusResponse updateReadyToQuoteStatus(Long transportId) {
        Transport transport = transportRepository.findById(transportId)
                .orElseThrow(() -> new RuntimeException("Transport not found"));

        List<RateCard> activeCards = rateCardRepository.findByTransportIdAndIsActiveTrue(transportId);
        LocalDateTime now = LocalDateTime.now();

        List<RateCard> validCards = activeCards.stream()
                .filter(card -> (card.getValidFrom() == null || !card.getValidFrom().isAfter(now))
                        && (card.getValidUntil() == null || card.getValidUntil().isAfter(now)))
                .collect(Collectors.toList());

        boolean ready = !validCards.isEmpty();
        LocalDateTime nextExpiry = validCards.stream()
                .map(RateCard::getValidUntil)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        transport.setReadyToQuote(ready);
        transport.setRateCardExpiresAt(nextExpiry);
        transportRepository.save(transport);

        ReadyToQuoteStatusResponse status = new ReadyToQuoteStatusResponse();
        status.setReadyToQuote(ready);
        status.setRateCardsCount(activeCards.size());
        status.setExpiredCardsCount((int) activeCards.stream()
                .filter(card -> card.getValidUntil() != null && card.getValidUntil().isBefore(now))
                .count());
        status.setNextExpiryAt(nextExpiry);
        if (!ready) {
            status.setReason("No active, non-expired rate card configured. Please set up your pricing.");
        }
        return status;
    }

    @Transactional(readOnly = true)
    public ReadyToQuoteStatusResponse getReadyToQuoteStatus(Long transportId) {
        Transport transport = transportRepository.findById(transportId)
                .orElseThrow(() -> new RuntimeException("Transport not found"));

        List<RateCard> activeCards = rateCardRepository.findByTransportIdAndIsActiveTrue(transportId);
        LocalDateTime now = LocalDateTime.now();

        ReadyToQuoteStatusResponse status = new ReadyToQuoteStatusResponse();
        status.setReadyToQuote(Boolean.TRUE.equals(transport.getReadyToQuote()));
        status.setRateCardsCount(activeCards.size());
        status.setExpiredCardsCount((int) activeCards.stream()
                .filter(card -> card.getValidUntil() != null && card.getValidUntil().isBefore(now))
                .count());
        status.setNextExpiryAt(transport.getRateCardExpiresAt());

        if (!Boolean.TRUE.equals(transport.getReadyToQuote())) {
            if (activeCards.isEmpty()) {
                status.setReason("You have no rate cards configured. Set up pricing to start receiving jobs.");
            } else {
                status.setReason("All rate cards are expired or not yet effective.");
            }
        }
        return status;
    }

    @Transactional
    public void captureRateCardSnapshotForQuotation(Long quotationId, Long transportId, Long categoryId) {
        if (quotationId == null || transportId == null) {
            return;
        }

        List<RateCard> candidates;
        if (categoryId != null) {
            candidates = rateCardRepository.findByTransportIdAndCategoryIdAndIsActiveTrue(transportId, categoryId);
        } else {
            candidates = rateCardRepository.findByTransportIdAndIsActiveTrue(transportId);
        }

        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        RateCard selected = candidates.stream()
                .filter(card -> (card.getValidFrom() == null || !card.getValidFrom().isAfter(now))
                        && (card.getValidUntil() == null || card.getValidUntil().isAfter(now)))
                .findFirst()
                .orElse(candidates.get(0));

        Map<String, Object> snapshotPayload = new java.util.HashMap<>();
        snapshotPayload.put("rate_card_id", selected.getRateCardId());
        snapshotPayload.put("transport_id", selected.getTransportId());
        snapshotPayload.put("category_id", selected.getCategoryId());
        snapshotPayload.put("base_price", selected.getBasePrice());
        snapshotPayload.put("price_per_km", selected.getPricePerKm());
        snapshotPayload.put("price_per_hour", selected.getPricePerHour());
        snapshotPayload.put("minimum_charge", selected.getMinimumCharge());
        snapshotPayload.put("valid_from", selected.getValidFrom());
        snapshotPayload.put("valid_until", selected.getValidUntil());

        String json;
        try {
            json = objectMapper.writeValueAsString(snapshotPayload);
        } catch (JsonProcessingException e) {
            // Do not block quotation if snapshot serialization fails
            json = null;
        }

        RateCardSnapshot snapshot = new RateCardSnapshot();
        snapshot.setQuotationId(quotationId);
        snapshot.setTransportId(transportId);
        snapshot.setRateCardId(selected.getRateCardId());
        snapshot.setCategoryId(selected.getCategoryId());
        snapshot.setPricingSnapshot(json);

        rateCardSnapshotRepository.save(snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.math.BigDecimal> parseAdditionalRules(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

