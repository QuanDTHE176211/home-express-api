package com.homeexpress.home_express_api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homeexpress.home_express_api.dto.response.RateCardResponse;
import com.homeexpress.home_express_api.dto.response.SuggestedPriceResponse;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingItem;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.BookingItemRepository;
import com.homeexpress.home_express_api.repository.BookingRepository;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final RateCardService rateCardService;
    private final com.homeexpress.home_express_api.repository.CategoryPricingRepository categoryPricingRepository;
    private final com.homeexpress.home_express_api.repository.VehiclePricingRepository vehiclePricingRepository;

    public PricingService(BookingRepository bookingRepository,
            BookingItemRepository bookingItemRepository,
            RateCardService rateCardService,
            com.homeexpress.home_express_api.repository.CategoryPricingRepository categoryPricingRepository,
            com.homeexpress.home_express_api.repository.VehiclePricingRepository vehiclePricingRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.rateCardService = rateCardService;
        this.categoryPricingRepository = categoryPricingRepository;
        this.vehiclePricingRepository = vehiclePricingRepository;
    }

    @Transactional(readOnly = true)
    public SuggestedPriceResponse calculateSuggestedPrice(Long bookingId, Long transportId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        List<BookingItem> items = bookingItemRepository.findByBookingId(bookingId);
        validateBookingReadyForPricing(booking, items);

        Long primaryCategoryId = resolvePrimaryCategoryId(items);

        List<RateCardResponse> rateCards = rateCardService.getRateCardsForTransport(transportId);
        if (rateCards == null || rateCards.isEmpty()) {
            throw new IllegalStateException("No rate cards configured for this transport. Cannot calculate suggested price.");
        }

        LocalDateTime now = LocalDateTime.now();
        // Calculate transport price using VehiclePricing (preferred) or fallback to RateCard
        // Default to VAN if no specific type requested
        List<com.homeexpress.home_express_api.entity.VehiclePricing> vehiclePricings = vehiclePricingRepository.findActiveByTransportAndVehicleType(transportId, com.homeexpress.home_express_api.entity.VehicleType.van);

        BigDecimal transportPrice = BigDecimal.ZERO;
        Long selectedVehiclePricingId = null;
        Long selectedRateCardId = null;
        BigDecimal minimumCharge = BigDecimal.ZERO;
        boolean minimumChargeApplied = false;

        // Note: Need to declare variables used in both branches
        BigDecimal distanceKm = booking.getDistanceKm() != null ? booking.getDistanceKm() : BigDecimal.ZERO;
        int itemCount = items != null ? items.stream()
                .map(item -> item.getQuantity() != null ? item.getQuantity() : 1)
                .reduce(0, Integer::sum) : 0;

        int durationMinutes = estimateDurationMinutes(distanceKm.doubleValue(), itemCount);
        BigDecimal estimatedHours = BigDecimal.valueOf(durationMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        if (!vehiclePricings.isEmpty()) {
            // Use the first active pricing found (e.g., Van)
            com.homeexpress.home_express_api.entity.VehiclePricing vp = vehiclePricings.get(0);
            selectedVehiclePricingId = vp.getVehiclePricingId();
            minimumCharge = defaultZero(vp.getMinChargeVnd());

            BigDecimal base = defaultZero(vp.getBasePriceVnd());

            BigDecimal kmPrice = BigDecimal.ZERO;
            BigDecimal dist = distanceKm;

            // Tier 1: First 4km
            BigDecimal tier1Km = dist.min(BigDecimal.valueOf(4));
            kmPrice = kmPrice.add(tier1Km.multiply(defaultZero(vp.getPerKmFirst4KmVnd())));
            dist = dist.subtract(tier1Km);

            // Tier 2: 5-40km (next 36km)
            if (dist.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tier2Km = dist.min(BigDecimal.valueOf(36));
                kmPrice = kmPrice.add(tier2Km.multiply(defaultZero(vp.getPerKm5To40KmVnd())));
                dist = dist.subtract(tier2Km);
            }

            // Tier 3: >40km
            if (dist.compareTo(BigDecimal.ZERO) > 0) {
                kmPrice = kmPrice.add(dist.multiply(defaultZero(vp.getPerKmAfter40KmVnd())));
            }

            transportPrice = base.add(kmPrice);

        } else {
            // Fallback to RateCard logic if no VehiclePricing
            if (rateCards == null || rateCards.isEmpty()) {
                throw new IllegalStateException("No pricing configuration (RateCard or VehiclePricing) found for this transport.");
            }

            // LocalDateTime now = LocalDateTime.now(); // 'now' is already defined above
            List<RateCardResponse> validCards = rateCards.stream()
                    .filter(card -> Boolean.TRUE.equals(card.getIsActive()))
                    .filter(card -> (card.getValidFrom() == null || !card.getValidFrom().isAfter(now))
                    && (card.getValidUntil() == null || card.getValidUntil().isAfter(now)))
                    .collect(Collectors.toList());

            if (validCards.isEmpty()) {
                throw new IllegalStateException("No active, non-expired rate card configured for this transport.");
            }

            RateCardResponse selected = null;
            if (primaryCategoryId != null) {
                selected = validCards.stream()
                        .filter(card -> Objects.equals(primaryCategoryId, card.getCategoryId()))
                        .findFirst()
                        .orElse(null);
            }
            if (selected == null) {
                selected = validCards.get(0);
            }

            selectedRateCardId = selected.getRateCardId();

            BigDecimal basePrice = defaultZero(selected.getBasePrice());
            BigDecimal distancePrice = defaultZero(selected.getPricePerKm())
                    .multiply(distanceKm)
                    .setScale(0, RoundingMode.HALF_UP);
            BigDecimal timePrice = defaultZero(selected.getPricePerHour())
                    .multiply(estimatedHours)
                    .setScale(0, RoundingMode.HALF_UP);

            transportPrice = basePrice.add(distancePrice).add(timePrice);
            minimumCharge = defaultZero(selected.getMinimumCharge());

            // Rate Card Multipliers logic
            Map<String, BigDecimal> rules = selected.getAdditionalRules();
            if (rules != null && !rules.isEmpty() && items != null && !items.isEmpty()) {
                BigDecimal multiplier = BigDecimal.ONE;
                boolean hasFragile = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getIsFragile()));
                boolean hasDisassembly = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getRequiresDisassembly()));
                BigDecimal heavyThreshold = BigDecimal.valueOf(80);
                boolean hasHeavy = items.stream().anyMatch(i -> i.getWeightKg() != null && i.getWeightKg().compareTo(heavyThreshold) > 0);

                BigDecimal fragileMultiplier = rules.get("fragile_multiplier");
                if (hasFragile && fragileMultiplier != null) {
                    multiplier = multiplier.multiply(fragileMultiplier);
                }

                BigDecimal disassemblyMultiplier = rules.get("disassembly_multiplier");
                if (hasDisassembly && disassemblyMultiplier != null) {
                    multiplier = multiplier.multiply(disassemblyMultiplier);
                }

                BigDecimal heavyMultiplier = rules.get("heavy_item_multiplier");
                if (hasHeavy && heavyMultiplier != null) {
                    multiplier = multiplier.multiply(heavyMultiplier);
                }

                transportPrice = transportPrice.multiply(multiplier);
            }
        }

        // Calculate items price using CategoryPricing
        BigDecimal totalItemsPrice = BigDecimal.ZERO;
        Map<String, BigDecimal> appliedMultipliers = new HashMap<>();

        List<com.homeexpress.home_express_api.entity.CategoryPricing> categoryPricings = categoryPricingRepository.findByTransport_TransportId(transportId);
        Map<Long, com.homeexpress.home_express_api.entity.CategoryPricing> pricingMap = categoryPricings.stream()
                .filter(cp -> Boolean.TRUE.equals(cp.getIsActive()))
                .collect(Collectors.toMap(cp -> cp.getCategory().getCategoryId(), cp -> cp, (p1, p2) -> p1));

        if (items != null) {
            for (BookingItem item : items) {
                if (item.getCategoryId() == null) {
                    continue;
                }

                com.homeexpress.home_express_api.entity.CategoryPricing cp = pricingMap.get(item.getCategoryId());
                if (cp != null) {
                    BigDecimal itemUnitCost = cp.getPricePerUnitVnd();

                    // Apply multipliers
                    if (Boolean.TRUE.equals(item.getIsFragile())) {
                        itemUnitCost = itemUnitCost.multiply(cp.getFragileMultiplier());
                        appliedMultipliers.put("fragile_multiplier", cp.getFragileMultiplier()); // Just for info
                    }
                    if (Boolean.TRUE.equals(item.getRequiresDisassembly())) {
                        itemUnitCost = itemUnitCost.multiply(cp.getDisassemblyMultiplier());
                        appliedMultipliers.put("disassembly_multiplier", cp.getDisassemblyMultiplier());
                    }
                    if (item.getWeightKg() != null && item.getWeightKg().compareTo(cp.getHeavyThresholdKg()) > 0) {
                        itemUnitCost = itemUnitCost.multiply(cp.getHeavyMultiplier());
                        appliedMultipliers.put("heavy_item_multiplier", cp.getHeavyMultiplier());
                    }

                    int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
                    totalItemsPrice = totalItemsPrice.add(itemUnitCost.multiply(BigDecimal.valueOf(quantity)));
                }
            }
        }

        BigDecimal subtotal = transportPrice.add(totalItemsPrice);
        BigDecimal roundedSubtotal = subtotal.setScale(0, RoundingMode.HALF_UP);

        BigDecimal suggestedTotal = roundedSubtotal;
        if (minimumCharge.compareTo(BigDecimal.ZERO) > 0 && roundedSubtotal.compareTo(minimumCharge) < 0) {
            suggestedTotal = minimumCharge;
            minimumChargeApplied = true;
        }

        SuggestedPriceResponse.PriceBreakdown breakdown = new SuggestedPriceResponse.PriceBreakdown();
        breakdown.setBasePrice(transportPrice);
        breakdown.setDistancePrice(BigDecimal.ZERO);
        breakdown.setTimePrice(BigDecimal.ZERO);
        breakdown.setMultipliers(appliedMultipliers);
        breakdown.setMinimumChargeApplied(minimumChargeApplied);

        SuggestedPriceResponse response = new SuggestedPriceResponse();
        response.setSuggestedPrice(suggestedTotal);
        response.setPriceBreakdown(breakdown);
        response.setRateCardId(selectedRateCardId);
        response.setVehiclePricingId(selectedVehiclePricingId);
        response.setCategoryId(primaryCategoryId);
        response.setCalculationTimestamp(LocalDateTime.now());

        log.debug("Calculated suggested price {} for booking {} and transport {}", suggestedTotal, bookingId, transportId);

        return response;
    }

    private void validateBookingReadyForPricing(Booking booking, List<BookingItem> items) {
        EnumSet<BookingStatus> allowedStatuses = EnumSet.of(BookingStatus.PENDING, BookingStatus.QUOTED);
        if (!allowedStatuses.contains(booking.getStatus())) {
            throw new IllegalStateException("Booking is not ready for pricing. Current status: " + booking.getStatus());
        }
        if (booking.getPickupAddress() == null || booking.getPickupAddress().isBlank()
                || booking.getDeliveryAddress() == null || booking.getDeliveryAddress().isBlank()) {
            throw new IllegalStateException("Booking is missing pickup or delivery address information");
        }
        if (booking.getPreferredDate() == null) {
            throw new IllegalStateException("Booking is missing preferred move date");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("Booking has no inventory items. Complete intake before requesting quotations.");
        }
    }

    private Long resolvePrimaryCategoryId(List<BookingItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (BookingItem item : items) {
            if (item.getCategoryId() != null) {
                return item.getCategoryId();
            }
        }
        return null;
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private int estimateDurationMinutes(double distanceKm, int itemCount) {
        int travel = (int) Math.round(Math.max(distanceKm, 1.0) / 28.0 * 60.0);
        int handling = itemCount * 10;
        int buffer = 20;
        return Math.max(45, travel + handling + buffer);
    }
}
