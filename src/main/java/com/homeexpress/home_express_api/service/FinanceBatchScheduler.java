package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.payout.PayoutDTO;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingSettlement;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.SettlementStatus;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.BookingSettlementRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FinanceBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinanceBatchScheduler.class);

    private static final long AUTO_SETTLEMENT_GRACE_HOURS = 24L;
    private static final long MIN_AUTO_PAYOUT_VND = 500_000L;

    private final BookingSettlementRepository settlementRepository;
    private final BookingRepository bookingRepository;
    private final SettlementService settlementService;
    private final PayoutService payoutService;

    /**
     * Auto-settle bookings that are either confirmed by customer or completed beyond the grace window.
     * Runs hourly by default (configurable via finance.auto-settlement-cron).
     */
    @Scheduled(cron = "${finance.auto-settlement-cron:0 10 * * * ?}")
    public void settleConfirmedOrExpiredBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(AUTO_SETTLEMENT_GRACE_HOURS);
        List<BookingSettlement> candidates = settlementRepository.findByStatusIn(
                List.of(SettlementStatus.PENDING, SettlementStatus.ON_HOLD));

        int processed = 0;
        for (BookingSettlement settlement : candidates) {
            Booking booking = bookingRepository.findById(settlement.getBookingId()).orElse(null);
            if (booking == null) {
                continue;
            }

            boolean customerConfirmed = booking.getStatus() == BookingStatus.CONFIRMED_BY_CUSTOMER;
            boolean autoEligible = booking.getStatus() == BookingStatus.COMPLETED
                    && booking.getActualEndTime() != null
                    && booking.getActualEndTime().isBefore(cutoff);

            if (!customerConfirmed && !autoEligible) {
                continue;
            }

            try {
                settlementService.processSettlement(settlement.getBookingId());
                processed++;
            } catch (Exception ex) {
                log.warn("Auto settlement skipped for booking {}: {}", settlement.getBookingId(), ex.getMessage());
            }
        }

        if (processed > 0) {
            log.info("Auto-settlement processed {} bookings (cutoff {} hours)", processed, AUTO_SETTLEMENT_GRACE_HOURS);
        }
    }

    /**
     * Auto-sweep payouts weekly (Tuesday 10:00 by default) for transports above the threshold.
     * Schedule is configurable via finance.auto-payout-cron.
     */
    @Scheduled(cron = "${finance.auto-payout-cron:0 0 10 ? * TUE}")
    public void autoSweepPayouts() {
        List<PayoutDTO> payouts = payoutService.autoSweepPayouts(MIN_AUTO_PAYOUT_VND);
        if (!payouts.isEmpty()) {
            log.info("Auto payout sweep created {} batch(es) with threshold {} VND", payouts.size(), MIN_AUTO_PAYOUT_VND);
        }
    }
}
