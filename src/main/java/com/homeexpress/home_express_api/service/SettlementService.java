package com.homeexpress.home_express_api.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homeexpress.home_express_api.dto.SettlementDTO;
import com.homeexpress.home_express_api.dto.SettlementEligibilityDTO;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingSettlement;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.CollectionMode;
import com.homeexpress.home_express_api.entity.Contract;
import com.homeexpress.home_express_api.entity.Incident;
import com.homeexpress.home_express_api.entity.IncidentStatus;
import com.homeexpress.home_express_api.entity.Payment;
import com.homeexpress.home_express_api.entity.PaymentMethod;
import com.homeexpress.home_express_api.entity.PaymentStatus;
import com.homeexpress.home_express_api.entity.PaymentType;
import com.homeexpress.home_express_api.entity.SettlementStatus;
import com.homeexpress.home_express_api.entity.TransportWallet;
import com.homeexpress.home_express_api.entity.WalletTransactionReferenceType;
import com.homeexpress.home_express_api.entity.WalletTransactionType;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.BookingSettlementRepository;
import com.homeexpress.home_express_api.repository.ContractRepository;
import com.homeexpress.home_express_api.repository.IncidentRepository;
import com.homeexpress.home_express_api.repository.PaymentRepository;

/**
 * Service for generating and managing settlement records from completed
 * bookings Handles commission calculation, payment verification, and settlement
 * status management
 */
@Service
public class SettlementService {

    private final BookingSettlementRepository settlementRepository;
    private final BookingRepository bookingRepository;
    private final ContractRepository contractRepository;
    private final PaymentRepository paymentRepository;
    private final IncidentRepository incidentRepository;
    private final CommissionService commissionService;
    private final WalletService walletService;

    public SettlementService(
            BookingSettlementRepository settlementRepository,
            BookingRepository bookingRepository,
            ContractRepository contractRepository,
            PaymentRepository paymentRepository,
            IncidentRepository incidentRepository,
            CommissionService commissionService,
            WalletService walletService) {
        this.settlementRepository = settlementRepository;
        this.bookingRepository = bookingRepository;
        this.contractRepository = contractRepository;
        this.paymentRepository = paymentRepository;
        this.incidentRepository = incidentRepository;
        this.commissionService = commissionService;
        this.walletService = walletService;
    }

    /**
     * Generate settlement record for a completed booking Validates eligibility,
     * calculates all amounts, and creates settlement record
     *
     * @param bookingId ID of the booking to settle
     * @return SettlementDTO with settlement details
     * @throws RuntimeException if booking is not eligible for settlement
     */
    @Transactional
    public SettlementDTO generateSettlement(Long bookingId) {
        if (settlementRepository.existsByBookingId(bookingId)) {
            throw new RuntimeException("Settlement already exists for booking ID: " + bookingId);
        }

        SettlementEligibilityDTO eligibility = checkEligibilityForSettlement(bookingId);
        if (!eligibility.isEligible()) {
            throw new RuntimeException("Booking not eligible for settlement: "
                    + String.join(", ", eligibility.getReasons()));
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        Contract contract = contractRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("Contract not found for booking"));

        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);

        SettlementAmounts amounts = calculateSettlementAmounts(booking, contract, payments);

        BookingSettlement settlement = new BookingSettlement();
        settlement.setBookingId(bookingId);
        settlement.setTransportId(booking.getTransportId());
        settlement.setAgreedPriceVnd(amounts.agreedPriceVnd);
        settlement.setTotalCollectedVnd(amounts.totalCollectedVnd);
        settlement.setGatewayFeeVnd(amounts.gatewayFeeVnd);
        settlement.setCommissionRateBps(amounts.commissionRateBps);
        settlement.setPlatformFeeVnd(amounts.platformFeeVnd);
        settlement.setAdjustmentVnd(0L);
        settlement.setCollectionMode(resolveCollectionMode(payments));
        settlement.setStatus(SettlementStatus.PENDING);
        settlement.setReadyAt(null);

        BookingSettlement savedSettlement = settlementRepository.save(settlement);
        // Removed auto-credit here. Credit happens only upon customer confirmation.
        // creditSettlementToWallet(savedSettlement, amounts.netToTransportVnd);
        return mapToDTO(savedSettlement);
    }

    /**
     * Process settlement after customer confirmation.
     * Verifies eligibility, sets status to READY, and credits the wallet.
     */
    @Transactional
    public SettlementDTO processSettlement(Long bookingId) {
        BookingSettlement settlement = settlementRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("Settlement not found for booking: " + bookingId));

        if (settlement.getStatus() == SettlementStatus.READY || settlement.getStatus() == SettlementStatus.PAID) {
            // Already processed
            return mapToDTO(settlement);
        }
        if (settlement.getStatus() == SettlementStatus.CANCELLED) {
            throw new RuntimeException("Settlement is cancelled and cannot be processed");
        }

        // Verify booking status (must be CONFIRMED_BY_CUSTOMER)
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        
        if (booking.getStatus() != BookingStatus.CONFIRMED_BY_CUSTOMER) {
             // Fallback: If customer didn't confirm but somehow we are here (e.g. Admin force), allow COMPLETED
             if (booking.getStatus() != BookingStatus.COMPLETED) {
                 throw new RuntimeException("Booking must be CONFIRMED_BY_CUSTOMER to process settlement.");
             }
        }

        // Verify fully paid
        SettlementEligibilityDTO eligibility = checkEligibilityForSettlement(bookingId);
        if (!eligibility.isEligible()) {
            String reason = String.join("; ", eligibility.getReasons());
            settlement.setOnHoldReason(reason);
            if (eligibility.getOpenIncidentCount() > 0) {
                settlement.setStatus(SettlementStatus.ON_HOLD);
            }
            settlementRepository.save(settlement);
            throw new RuntimeException("Booking not eligible for settlement: " + reason);
        }

        BigDecimal totalCollected = paymentRepository.sumAmountByBookingIdAndStatus(
                bookingId, PaymentStatus.COMPLETED);
        long collected = totalCollected != null ? totalCollected.longValue() : 0L;
        if (collected < settlement.getAgreedPriceVnd()) {
            settlement.setOnHoldReason("Booking not fully paid. Collected " + collected + " VND");
            settlementRepository.save(settlement);
            throw new RuntimeException("Booking not fully paid. Cannot process settlement.");
        }

        settlement.setStatus(SettlementStatus.READY);
        settlement.setReadyAt(LocalDateTime.now());
        settlement.setOnHoldReason(null);
        
        BookingSettlement saved = settlementRepository.save(settlement);
        
        // Credit the wallet
        creditSettlementToWallet(saved, resolveNetAmount(saved));
        
        return mapToDTO(saved);
    }

    /**
     * Check if a booking is eligible for settlement Requirements: - Booking
     * must be COMPLETED - Must be fully paid (total collected >= agreed price)
     * - Must have no open incidents (REPORTED, UNDER_INVESTIGATION)
     *
     * @param bookingId ID of the booking to check
     * @return SettlementEligibilityDTO with eligibility status and reasons
     */
    public SettlementEligibilityDTO checkEligibilityForSettlement(Long bookingId) {
        SettlementEligibilityDTO result = new SettlementEligibilityDTO();
        result.setBookingId(bookingId);
        result.setEligible(true);

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            result.setEligible(false);
            result.addReason("Booking not found");
            return result;
        }

        result.setBookingStatus(booking.getStatus().name());

        if (booking.getStatus() != BookingStatus.COMPLETED && booking.getStatus() != BookingStatus.CONFIRMED_BY_CUSTOMER) {
            result.setEligible(false);
            result.addReason("Booking is not COMPLETED or CONFIRMED_BY_CUSTOMER (current status: " + booking.getStatus() + ")");
        }

        if (booking.getTransportId() == null) {
            result.setEligible(false);
            result.addReason("Booking has no assigned transport");
        }

        Contract contract = contractRepository.findByBookingId(bookingId).orElse(null);
        if (contract == null) {
            result.setEligible(false);
            result.addReason("No contract found for booking");
            return result;
        }

        long agreedPrice = contract.getAgreedPriceVnd();
        result.setAgreedPriceVnd(agreedPrice);

        BigDecimal totalCollected = paymentRepository.sumAmountByBookingIdAndStatus(
                bookingId, PaymentStatus.COMPLETED
        );
        long totalCollectedVnd = totalCollected != null ? totalCollected.longValue() : 0L;
        result.setTotalCollectedVnd(totalCollectedVnd);

        boolean fullyPaid = totalCollectedVnd >= agreedPrice;
        result.setFullyPaid(fullyPaid);

        if (!fullyPaid) {
            result.setEligible(false);
            result.addReason(String.format(
                    "Booking not fully paid (collected: %d VND, required: %d VND)",
                    totalCollectedVnd, agreedPrice
            ));
        }

        List<Incident> openIncidents = incidentRepository.findByBookingIdAndStatusOrderByReportedAtDesc(
                bookingId, IncidentStatus.REPORTED
        );
        List<Incident> investigatingIncidents = incidentRepository.findByBookingIdAndStatusOrderByReportedAtDesc(
                bookingId, IncidentStatus.UNDER_INVESTIGATION
        );

        int openIncidentCount = openIncidents.size() + investigatingIncidents.size();
        result.setOpenIncidentCount(openIncidentCount);

        if (openIncidentCount > 0) {
            result.setEligible(false);
            result.addReason(String.format(
                    "Booking has %d open incident(s) (REPORTED or UNDER_INVESTIGATION)",
                    openIncidentCount
            ));
        }

        return result;
    }

    /**
     * Calculate all settlement amounts including fees and commissions
     *
     * @param booking the booking entity
     * @param contract the contract entity
     * @param payments list of completed payments
     * @return SettlementAmounts with all calculated amounts
     */
    public SettlementAmounts calculateSettlementAmounts(Booking booking, Contract contract, List<Payment> payments) {
        SettlementAmounts amounts = new SettlementAmounts();

        amounts.agreedPriceVnd = contract.getAgreedPriceVnd();

        List<Payment> completedPayments = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .filter(p -> p.getPaymentType() != PaymentType.REFUND)
                .collect(Collectors.toList());

        amounts.totalCollectedVnd = completedPayments.stream()
                .map(Payment::getAmount)
                .map(BigDecimal::longValue)
                .reduce(0L, Long::sum);

        // In the current version, all customer payments are cash or manual bank transfer
        // with no third-party gateway markup, so gatewayFeeTotal will evaluate to 0.
        // This loop is kept as a hook for future online/gateway payment methods.
        long gatewayFeeTotal = 0L;
        int onlinePaymentCount = 0;
        for (Payment payment : completedPayments) {
            if (isOnlinePayment(payment.getPaymentMethod())) {
                gatewayFeeTotal += commissionService.calculateGatewayFee(payment.getAmount().longValue());
                onlinePaymentCount++;
            }
        }
        amounts.gatewayFeeVnd = gatewayFeeTotal;

        amounts.commissionRateBps = commissionService.getCommissionRateBps(booking.getTransportId());
        amounts.platformFeeVnd = commissionService.calculatePlatformFee(
                amounts.agreedPriceVnd, booking.getTransportId()
        );
        amounts.netToTransportVnd = commissionService.calculateNetToTransport(
                amounts.totalCollectedVnd,
                amounts.gatewayFeeVnd,
                amounts.platformFeeVnd,
                0L
        );

        return amounts;
    }

    /**
     * Resolve collection mode based on payment methods used
     */
    private CollectionMode resolveCollectionMode(List<Payment> payments) {
        if (payments == null || payments.isEmpty()) {
            return CollectionMode.ALL_CASH; // default
        }
        
        boolean hasCash = payments.stream().anyMatch(p -> p.getPaymentMethod() == PaymentMethod.CASH);
        boolean hasOnline = payments.stream().anyMatch(p -> p.getPaymentMethod() == PaymentMethod.BANK_TRANSFER);
        
        if (hasCash && hasOnline) {
            return CollectionMode.MIXED;
        } else if (hasOnline) {
            return CollectionMode.ALL_ONLINE;
        } else {
            return CollectionMode.ALL_CASH;
        }
    }

    /**
     * Update settlement status with optional reason
     *
     * @param settlementId ID of the settlement
     * @param newStatus new status to set
     * @param reason optional reason for status change (required for ON_HOLD)
     * @return updated SettlementDTO
     * @throws RuntimeException if settlement not found or invalid status
     * transition
     */
    @Transactional
    public SettlementDTO updateSettlementStatus(Long settlementId, SettlementStatus newStatus, String reason) {
        BookingSettlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new RuntimeException("Settlement not found"));

        SettlementStatus previousStatus = settlement.getStatus();

        validateStatusTransition(settlement.getStatus(), newStatus);

        settlement.setStatus(newStatus);

        switch (newStatus) {
            case READY:
                if (settlement.getReadyAt() == null) {
                    settlement.setReadyAt(LocalDateTime.now());
                }
                settlement.setOnHoldReason(null);
                break;
            case ON_HOLD:
                if (reason == null || reason.trim().isEmpty()) {
                    throw new RuntimeException("Reason is required when setting status to ON_HOLD");
                }
                settlement.setOnHoldReason(reason);
                break;
            case PAID:
                if (settlement.getPaidAt() == null) {
                    settlement.setPaidAt(LocalDateTime.now());
                }
                break;
            case CANCELLED:
                if (reason != null && !reason.trim().isEmpty()) {
                    settlement.setNotes(reason);
                }
                break;
        }

        BookingSettlement updated = settlementRepository.save(settlement);

        if (newStatus == SettlementStatus.READY && previousStatus != SettlementStatus.READY) {
            creditSettlementToWallet(updated, updated.getNetToTransportVnd());
        }

        return mapToDTO(updated);
    }

    private void validateStatusTransition(SettlementStatus currentStatus, SettlementStatus newStatus) {
        if (currentStatus == SettlementStatus.PAID) {
            throw new RuntimeException("Cannot change status of a PAID settlement");
        }
        if (currentStatus == SettlementStatus.CANCELLED) {
            throw new RuntimeException("Cannot change status of a CANCELLED settlement");
        }
        if (newStatus == SettlementStatus.PAID && currentStatus != SettlementStatus.READY) {
            throw new RuntimeException("Settlement must be in READY status before marking as PAID");
        }
    }

    private boolean isOnlinePayment(PaymentMethod method) {
        // After removing //ZaloPay integrations, only BANK_TRANSFER is treated as online.
        return method == PaymentMethod.BANK_TRANSFER;
    }

    private SettlementDTO mapToDTO(BookingSettlement settlement) {
        SettlementDTO dto = new SettlementDTO();
        dto.setSettlementId(settlement.getSettlementId());
        dto.setBookingId(settlement.getBookingId());
        dto.setTransportId(settlement.getTransportId());
        dto.setAgreedPriceVnd(settlement.getAgreedPriceVnd());
        dto.setTotalCollectedVnd(settlement.getTotalCollectedVnd());
        dto.setGatewayFeeVnd(settlement.getGatewayFeeVnd());
        dto.setCommissionRateBps(settlement.getCommissionRateBps());
        dto.setPlatformFeeVnd(settlement.getPlatformFeeVnd());
        dto.setAdjustmentVnd(settlement.getAdjustmentVnd());
        dto.setNetToTransportVnd(settlement.getNetToTransportVnd());
        dto.setCollectionMode(settlement.getCollectionMode());
        dto.setStatus(settlement.getStatus());
        dto.setOnHoldReason(settlement.getOnHoldReason());
        dto.setCreatedAt(settlement.getCreatedAt());
        dto.setReadyAt(settlement.getReadyAt());
        dto.setPaidAt(settlement.getPaidAt());
        dto.setUpdatedAt(settlement.getUpdatedAt());
        dto.setNotes(settlement.getNotes());
        return dto;
    }

    public List<SettlementDTO> getAllSettlements() {
        List<BookingSettlement> settlements = settlementRepository.findAllOrderByCreatedAtDesc();
        return settlements.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    public List<SettlementDTO> getSettlementsByStatus(SettlementStatus status) {
        List<BookingSettlement> settlements = settlementRepository.findByStatus(status);
        return settlements.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    public List<SettlementDTO> getSettlementsByTransport(Long transportId) {
        List<BookingSettlement> settlements = settlementRepository.findByTransportId(transportId);
        return settlements.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    public SettlementDTO getSettlementById(Long settlementId) {
        BookingSettlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new RuntimeException("Settlement not found"));
        return mapToDTO(settlement);
    }

    public List<SettlementDTO> getReadySettlementsGroupedByTransport() {
        List<BookingSettlement> settlements = settlementRepository.findByStatusOrderByReadyAtDesc(SettlementStatus.READY);
        return settlements.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    public static class SettlementAmounts {

        public long agreedPriceVnd;
        public long totalCollectedVnd;
        // Bank transfer / gateway fee in VND; currently always 0 in this version.
        public long gatewayFeeVnd;
        public int commissionRateBps;
        public long platformFeeVnd;
        public long netToTransportVnd;
    }

    private long resolveNetAmount(BookingSettlement settlement) {
        if (settlement == null) {
            return 0L;
        }
        if (settlement.getNetToTransportVnd() != null) {
            return settlement.getNetToTransportVnd();
        }
        return commissionService.calculateNetToTransport(
                defaultZero(settlement.getTotalCollectedVnd()),
                defaultZero(settlement.getGatewayFeeVnd()),
                defaultZero(settlement.getPlatformFeeVnd()),
                defaultZero(settlement.getAdjustmentVnd())
        );
    }

    private long defaultZero(Long value) {
        return value != null ? value : 0L;
    }

    private void creditSettlementToWallet(BookingSettlement settlement, Long overrideAmount) {
        if (settlement == null) {
            return;
        }
        long amount = 0L;
        if (overrideAmount != null) {
            amount = overrideAmount;
        } else if (settlement.getNetToTransportVnd() != null) {
            amount = settlement.getNetToTransportVnd();
        }
        if (amount <= 0) {
            return;
        }
        if (walletService.hasSettlementCredit(settlement.getSettlementId())) {
            return;
        }
        TransportWallet wallet = walletService.getOrCreateWallet(settlement.getTransportId());
        walletService.creditWallet(wallet.getWalletId(), amount,
                WalletTransactionType.SETTLEMENT_CREDIT,
                WalletTransactionReferenceType.SETTLEMENT,
                settlement.getSettlementId(),
                "Settlement for booking " + settlement.getBookingId(),
                null);
    }
}
