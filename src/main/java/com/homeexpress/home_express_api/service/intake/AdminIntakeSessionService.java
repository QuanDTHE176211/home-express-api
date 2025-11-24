package com.homeexpress.home_express_api.service.intake;

import com.homeexpress.home_express_api.dto.intake.ItemCandidateDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.booking.AddressDto;
import com.homeexpress.home_express_api.dto.booking.BookingRequest;
import com.homeexpress.home_express_api.dto.intake.PublishSessionRequest;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionDetailResponse;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionListResponse;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionStatsResponse;
import com.homeexpress.home_express_api.dto.booking.BookingResponse;
import com.homeexpress.home_express_api.entity.IntakeSession;
import com.homeexpress.home_express_api.entity.IntakeSessionItem;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.repository.IntakeSessionRepository;
import com.homeexpress.home_express_api.repository.IntakeSessionItemRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/**
 * Service for admin management of intake sessions.
 * Provides QA, override, and publishing capabilities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminIntakeSessionService {

    private final IntakeSessionRepository sessionRepository;
    private final IntakeSessionItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    // Store active SSE emitters for log streaming
    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    /**
     * List all intake sessions with pagination
     */
    @Transactional(readOnly = true)
    public AdminIntakeSessionListResponse listSessions(String status, Long customerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<IntakeSession> sessions;

        if (status != null && customerId != null) {
            sessions = sessionRepository.findAll(pageable); // TODO: Add filtering by status and customerId
        } else if (status != null) {
            sessions = sessionRepository.findAll(pageable); // TODO: Add filtering by status
        } else if (customerId != null) {
            User user = userRepository.findById(customerId).orElse(null);
            if (user != null) {
                List<IntakeSession> userSessions = sessionRepository.findByUserAndStatus(user, "active");
                // Convert to page manually
                int start = (int) pageable.getOffset();
                int end = Math.min(start + pageable.getPageSize(), userSessions.size());
                List<IntakeSession> pagedSessions = userSessions.subList(start, end);
                // Create a custom page response
                return AdminIntakeSessionListResponse.builder()
                        .sessions(pagedSessions.stream()
                                .map(this::mapToDetailResponse)
                                .collect(Collectors.toList()))
                        .total(userSessions.size())
                        .page(page)
                        .size(size)
                        .build();
            }
            sessions = Page.empty(pageable);
        } else {
            sessions = sessionRepository.findAll(pageable);
        }

        List<AdminIntakeSessionDetailResponse> sessionList = sessions.getContent().stream()
                .map(this::mapToDetailResponse)
                .collect(Collectors.toList());

        return AdminIntakeSessionListResponse.builder()
                .sessions(sessionList)
                .total((int) sessions.getTotalElements())
                .page(page)
                .size(size)
                .build();
    }

    /**
     * Get session statistics by status
     */
    @Transactional(readOnly = true)
    public AdminIntakeSessionStatsResponse getSessionStats(String status) {
        long total = sessionRepository.countByStatus(status);
        Double avgConfidence = sessionRepository.getAverageConfidenceByStatus(status);
        LocalDateTime oldestCreatedAt = sessionRepository.getOldestCreatedAtByStatus(status);
        
        long oldestWaitTime = 0;
        if (oldestCreatedAt != null) {
            oldestWaitTime = java.time.Duration.between(oldestCreatedAt, LocalDateTime.now()).getSeconds();
        }
        
        return AdminIntakeSessionStatsResponse.builder()
                .total(total)
                .avgConfidence(avgConfidence != null ? avgConfidence : 0.0)
                .oldestWaitTimeSeconds(oldestWaitTime)
                .build();
    }

    /**
     * Get detailed session information
     */
    @Transactional(readOnly = true)
    public AdminIntakeSessionDetailResponse getSessionDetail(String sessionId) {
        IntakeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        return mapToDetailResponse(session);
    }

    /**
     * Get session items
     */
    @Transactional(readOnly = true)
    public List<ItemCandidateDto> getSessionItems(String sessionId) {
        IntakeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        return session.getItems().stream()
                .map(this::mapItemToDto)
                .collect(Collectors.toList());
    }

    /**
     * Update session items (admin override)
     */
    @Transactional
    public void updateSessionItems(String sessionId, List<ItemCandidateDto> items, Long adminUserId) {
        IntakeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        // Clear existing items
        session.getItems().clear();
        itemRepository.deleteAll(itemRepository.findBySessionSessionId(sessionId));

        // Add new items
        for (ItemCandidateDto itemDto : items) {
            IntakeSessionItem item = mapDtoToItem(itemDto, session);
            session.addItem(item);
        }

        sessionRepository.save(session);
        log.info("Admin {} updated {} items in session {}", adminUserId, items.size(), sessionId);
    }

    /**
     * Rerun AI detection for a session
     */
    @Transactional
    public void rerunAIDetection(String sessionId, boolean forceReprocess, Long adminUserId) {
        sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        log.info("Admin {} rerunning AI detection for session {} (forceReprocess: {})", 
                adminUserId, sessionId, forceReprocess);

        // TODO: Implement actual AI rerun with SSE logging
        // For now, just log the action
        sendSSEEvent(sessionId, "info", "AI detection rerun initiated by admin");
        
        // In a real implementation, this would:
        // 1. Get original image URLs from metadata
        // 2. Call AIDetectionService
        // 3. Stream logs via SSE
        // 4. Update session with new results
    }

    /**
     * Force a custom quote (admin override)
     */
    @Transactional
    public void forceQuote(String sessionId, double price, String notes, Long adminUserId) {
        IntakeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        // Store forced quote in metadata
        String metadata = String.format(
                "{\"forced_quote\":{\"price\":%.2f,\"notes\":\"%s\",\"forced_by\":%d,\"forced_at\":\"%s\"}}",
                price, notes != null ? notes.replace("\"", "\\\"") : "", adminUserId, LocalDateTime.now());

        session.setMetadata(metadata);
        sessionRepository.save(session);

        log.info("Admin {} forced quote {} for session {}", adminUserId, price, sessionId);
        sendSSEEvent(sessionId, "info", String.format("Forced quote applied: %.2f VND", price));
    }

    /**
     * Publish session to booking
     */
    @Transactional
    public Long publishSession(String sessionId, PublishSessionRequest publishRequest, Long adminUserId) {
        IntakeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        if (session.getUser() == null) {
            throw new RuntimeException("Session has no associated user");
        }

        if (session.getItems() == null || session.getItems().isEmpty()) {
            throw new IllegalStateException("Session has no items to convert into a booking");
        }

        BookingRequest bookingRequest = buildBookingRequest(session, publishRequest);
        BookingResponse booking = bookingService.createBooking(bookingRequest, session.getUser().getUserId(), UserRole.MANAGER);

        LocalDateTime publishedAt = LocalDateTime.now();
        LocalDateTime biddingExpiresAt = determineExpiry(publishRequest.getExpiresInHours(), publishedAt);

        session.setStatus("published");
        session.setMetadata(mergePublishMetadata(session.getMetadata(), sessionId, booking.getBookingId(),
                adminUserId, publishedAt, biddingExpiresAt));

        sessionRepository.save(session);

        log.info("Admin {} published session {} to booking #{} (expires in {})",
                adminUserId, sessionId, booking.getBookingId(),
                publishRequest.getExpiresInHours() != null ? publishRequest.getExpiresInHours() : 24);
        sendSSEEvent(sessionId, "info", "Session published to booking #" + booking.getBookingId());

        return booking.getBookingId();
    }

    /**
     * Create SSE event stream for real-time logs
     */
    public SseEmitter createEventStream(String sessionId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minutes timeout

        activeEmitters.put(sessionId, emitter);

        emitter.onCompletion(() -> {
            activeEmitters.remove(sessionId);
            log.debug("SSE emitter completed for session {}", sessionId);
        });

        emitter.onTimeout(() -> {
            activeEmitters.remove(sessionId);
            log.debug("SSE emitter timeout for session {}", sessionId);
        });

        emitter.onError((ex) -> {
            activeEmitters.remove(sessionId);
            log.error("SSE emitter error for session {}: {}", sessionId, ex.getMessage());
        });

        // Send initial connection message
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"message\":\"Connected to session log stream\"}"));
        } catch (IOException e) {
            log.error("Error sending initial SSE message: {}", e.getMessage());
        }

        return emitter;
    }

    /**
     * Send SSE event to all active emitters for a session
     */
    private void sendSSEEvent(String sessionId, String level, String message) {
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter != null) {
            try {
                String eventData = String.format(
                        "{\"timestamp\":\"%s\",\"level\":\"%s\",\"message\":\"%s\"}",
                        LocalDateTime.now(), level, message.replace("\"", "\\\""));

                emitter.send(SseEmitter.event()
                        .name("log")
                        .data(eventData));
            } catch (IOException e) {
                log.error("Error sending SSE event: {}", e.getMessage());
                activeEmitters.remove(sessionId);
            }
        }
    }

    // ========================================================================
    // MAPPING METHODS
    // ========================================================================

    private BookingRequest buildBookingRequest(IntakeSession session, PublishSessionRequest publishRequest) {
        BookingRequest bookingRequest = new BookingRequest();

        AddressDto pickup = publishRequest.getPickupAddress();
        AddressDto delivery = publishRequest.getDeliveryAddress();

        if (pickup == null || delivery == null) {
            throw new IllegalArgumentException("Pickup and delivery addresses are required before publishing");
        }

        if (publishRequest.getPreferredDate() == null) {
            throw new IllegalArgumentException("Preferred date is required before publishing");
        }

        bookingRequest.setPickupAddress(pickup);
        bookingRequest.setDeliveryAddress(delivery);
        bookingRequest.setPreferredDate(publishRequest.getPreferredDate());
        bookingRequest.setPreferredTimeSlot(publishRequest.getPreferredTimeSlot());
        bookingRequest.setNotes(publishRequest.getNotes());
        bookingRequest.setSpecialRequirements(publishRequest.getSpecialRequirements());

        List<BookingRequest.ItemDto> items = session.getItems().stream()
                .map(this::mapToBookingItemDto)
                .collect(Collectors.toList());
        bookingRequest.setItems(items);

        return bookingRequest;
    }

    private BookingRequest.ItemDto mapToBookingItemDto(IntakeSessionItem item) {
        BookingRequest.ItemDto dto = new BookingRequest.ItemDto();
        dto.setCategoryId(null); // Intake session keeps category label only
        dto.setName(item.getName());
        dto.setBrand(null);
        dto.setModel(null);
        dto.setQuantity(item.getQuantity() != null ? item.getQuantity() : 1);
        dto.setWeight(item.getWeightKg());
        dto.setIsFragile(Boolean.TRUE.equals(item.getIsFragile()));
        dto.setRequiresDisassembly(Boolean.TRUE.equals(item.getRequiresDisassembly()));
        dto.setRequiresPackaging(Boolean.FALSE);
        dto.setDeclaredValueVnd(null);

        if (StringUtils.hasText(item.getImageUrl())) {
            dto.setImageUrls(Collections.singletonList(item.getImageUrl()));
        }

        return dto;
    }

    private LocalDateTime determineExpiry(Integer expiresInHours, LocalDateTime publishedAt) {
        int effectiveHours = (expiresInHours == null || expiresInHours <= 0) ? 24 : expiresInHours;
        return publishedAt.plusHours(effectiveHours);
    }

    private String mergePublishMetadata(
            String existingMetadata,
            String sessionId,
            Long bookingId,
            Long adminUserId,
            LocalDateTime publishedAt,
            LocalDateTime biddingExpiresAt) {

        try {
            Map<String, Object> metadata = StringUtils.hasText(existingMetadata)
                    ? objectMapper.readValue(existingMetadata, new TypeReference<Map<String, Object>>() {})
                    : new HashMap<>();

            Map<String, Object> publishInfo = new HashMap<>();
            publishInfo.put("booking_id", bookingId);
            publishInfo.put("published_by", adminUserId);
            publishInfo.put("published_at", publishedAt.toString());
            publishInfo.put("bidding_expires_at", biddingExpiresAt.toString());

            metadata.put("publish_info", publishInfo);
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to merge publish metadata for session {}: {}", sessionId, e.getMessage());
            return String.format(
                    "{\"publish_info\":{\"booking_id\":%d,\"published_by\":%d,\"published_at\":\"%s\",\"bidding_expires_at\":\"%s\"}}",
                    bookingId,
                    adminUserId,
                    publishedAt,
                    biddingExpiresAt);
        }
    }

    private AdminIntakeSessionDetailResponse mapToDetailResponse(IntakeSession session) {
        User user = session.getUser();
        CustomerInfo customerInfo = null;
        
        if (user != null) {
            customerInfo = CustomerInfo.builder()
                    .customerId(user.getUserId())
                    .customerName(user.getEmail()) // Use email as name
                    .customerEmail(user.getEmail())
                    .customerAvatar(null) 
                    .build();
        }

        // Parse forced quote from metadata if exists
        Double forcedQuotePrice = null;
        if (session.getMetadata() != null && session.getMetadata().contains("\"forced_quote\"")) {
            // Simple parsing - in production, use proper JSON parsing
            try {
                String metadata = session.getMetadata();
                int priceStart = metadata.indexOf("\"price\":") + 8;
                int priceEnd = metadata.indexOf(",", priceStart);
                if (priceEnd == -1) priceEnd = metadata.indexOf("}", priceStart);
                if (priceStart > 8 && priceEnd > priceStart) {
                    String priceStr = metadata.substring(priceStart, priceEnd).trim();
                    forcedQuotePrice = Double.parseDouble(priceStr);
                }
            } catch (Exception e) {
                log.warn("Failed to parse forced quote from metadata: {}", e.getMessage());
            }
        }

        List<String> imageUrls = session.getItems().stream()
                .map(IntakeSessionItem::getImageUrl)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());

        return AdminIntakeSessionDetailResponse.builder()
                .sessionId(session.getSessionId())
                .customerId(user != null ? user.getUserId() : null)
                .status(session.getStatus())
                .imageUrls(imageUrls)
                .imageCount(imageUrls.size())
                .detectionResults(session.getMetadata())
                .averageConfidence(session.getAverageConfidence() != null 
                        ? session.getAverageConfidence().doubleValue() : null)
                .items(session.getItems().stream()
                        .map(this::mapItemToDto)
                        .collect(Collectors.toList()))
                .estimatedPrice(null) // TODO: Calculate from items
                .estimatedWeightKg(session.getItems().stream()
                        .map(IntakeSessionItem::getWeightKg)
                        .filter(w -> w != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .doubleValue())
                .estimatedVolumeM3(session.getEstimatedVolume() != null
                        ? session.getEstimatedVolume().doubleValue() : null)
                .forcedQuotePrice(forcedQuotePrice)
                .aiServiceUsed(session.getAiServiceUsed())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .expiresAt(session.getExpiresAt())
                .customer(customerInfo)
                .build();
    }

    private ItemCandidateDto mapItemToDto(IntakeSessionItem item) {
        ItemCandidateDto.DimensionsDto dimensions = null;
        if (item.getWidthCm() != null && item.getHeightCm() != null && item.getLengthCm() != null) {
            dimensions = ItemCandidateDto.DimensionsDto.builder()
                    .widthCm(item.getWidthCm().doubleValue())
                    .heightCm(item.getHeightCm().doubleValue())
                    .depthCm(item.getLengthCm().doubleValue())
                    .build();
        }

        return ItemCandidateDto.builder()
                .id(item.getItemId())
                .name(item.getName())
                .categoryName(item.getCategory())
                .quantity(item.getQuantity())
                .weightKg(item.getWeightKg() != null ? item.getWeightKg().doubleValue() : null)
                .dimensions(dimensions)
                .isFragile(item.getIsFragile())
                .requiresDisassembly(item.getRequiresDisassembly())
                .imageUrl(item.getImageUrl())
                .notes(item.getNotes())
                .source(item.getSource())
                .confidence(item.getConfidence() != null ? item.getConfidence().doubleValue() : null)
                .build();
    }

    private IntakeSessionItem mapDtoToItem(ItemCandidateDto dto, IntakeSession session) {
        IntakeSessionItem.IntakeSessionItemBuilder builder = IntakeSessionItem.builder()
                .itemId(dto.getId())
                .name(dto.getName())
                .category(dto.getCategoryName())
                .quantity(dto.getQuantity() != null ? dto.getQuantity() : 1)
                .isFragile(dto.getIsFragile() != null ? dto.getIsFragile() : false)
                .requiresDisassembly(dto.getRequiresDisassembly() != null ? dto.getRequiresDisassembly() : false)
                .imageUrl(dto.getImageUrl())
                .notes(dto.getNotes())
                .source(dto.getSource())
                .session(session);

        if (dto.getWeightKg() != null) {
            builder.weightKg(BigDecimal.valueOf(dto.getWeightKg()));
        }

        if (dto.getDimensions() != null) {
            ItemCandidateDto.DimensionsDto dims = dto.getDimensions();
            if (dims.getWidthCm() != null) {
                builder.widthCm(BigDecimal.valueOf(dims.getWidthCm()));
            }
            if (dims.getHeightCm() != null) {
                builder.heightCm(BigDecimal.valueOf(dims.getHeightCm()));
            }
            if (dims.getDepthCm() != null) {
                builder.lengthCm(BigDecimal.valueOf(dims.getDepthCm()));
            }

            if (dims.getWidthCm() != null && dims.getHeightCm() != null && dims.getDepthCm() != null) {
                double volumeM3 = (dims.getWidthCm() * dims.getHeightCm() * dims.getDepthCm()) / 1_000_000.0;
                builder.volumeM3(BigDecimal.valueOf(volumeM3));
            }
        }

        if (dto.getConfidence() != null) {
            builder.confidence(BigDecimal.valueOf(dto.getConfidence()));
            builder.aiDetected(true);
        }

        return builder.build();
    }

    // Inner class for customer info
    @lombok.Data
    @lombok.Builder
    public static class CustomerInfo {
        private Long customerId;
        private String customerName;
        private String customerEmail;
        private String customerAvatar;
    }
}
