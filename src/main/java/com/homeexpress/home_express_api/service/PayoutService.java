package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.payout.PayoutDTO;
import com.homeexpress.home_express_api.dto.payout.PayoutItemDTO;
import com.homeexpress.home_express_api.entity.BookingSettlement;
import com.homeexpress.home_express_api.entity.PayoutStatus;
import com.homeexpress.home_express_api.entity.SettlementStatus;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.TransportPayout;
import com.homeexpress.home_express_api.entity.TransportPayoutItem;
import com.homeexpress.home_express_api.entity.TransportWallet;
import com.homeexpress.home_express_api.entity.WalletTransactionReferenceType;
import com.homeexpress.home_express_api.entity.WalletTransactionType;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.integration.payout.ExternalPayoutGateway;
import com.homeexpress.home_express_api.integration.payout.ExternalPayoutResult;
import com.homeexpress.home_express_api.repository.BookingSettlementRepository;
import com.homeexpress.home_express_api.repository.TransportPayoutItemRepository;
import com.homeexpress.home_express_api.repository.TransportPayoutRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

/**
 * Service for managing transport payouts and settlements.
 * Handles batch payout creation, status updates, and payout queries.
 */
@RequiredArgsConstructor
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final TransportPayoutRepository payoutRepository;

    private final TransportPayoutItemRepository payoutItemRepository;

    private final BookingSettlementRepository settlementRepository;

    private final TransportRepository transportRepository;

    private final WalletService walletService;

    private final ExternalPayoutGateway externalPayoutGateway;

    /**
     * Creates a payout batch for a specific transport from all READY settlements.
     *
     * @param transportId the transport company ID
     * @return the created payout DTO
     * @throws ResourceNotFoundException if transport not found
     * @throws IllegalStateException if no READY settlements found
     */
    @Transactional
    public PayoutDTO createPayoutBatch(Long transportId) {
        Transport transport = transportRepository.findById(transportId)
                .orElseThrow(() -> new ResourceNotFoundException("Transport", "id", transportId));

        List<BookingSettlement> readySettlements = settlementRepository
                .findByTransportIdAndStatus(transportId, SettlementStatus.READY)
                .stream()
                .filter(settlement -> settlement.getPayoutId() == null)
                .filter(settlement -> netAmount(settlement) > 0)
                .collect(Collectors.toList());

        if (readySettlements.isEmpty()) {
            throw new IllegalStateException("No READY settlements with payable amount found for transport ID: " + transportId);
        }

        long totalAmount = readySettlements.stream()
                .mapToLong(this::netAmount)
                .sum();

        TransportWallet wallet = walletService.getOrCreateWallet(transportId);
        if (wallet.getCurrentBalanceVnd() < totalAmount) {
            throw new IllegalStateException("Insufficient wallet balance to create payout batch");
        }

        TransportPayout payout = new TransportPayout();
        payout.setTransportId(transportId);
        payout.setPayoutNumber(generatePayoutNumber(transportId));
        payout.setTotalAmountVnd(totalAmount);
        payout.setItemCount(readySettlements.size());
        payout.setStatus(PayoutStatus.PENDING);
        payout.setBankCode(transport.getBankCode());
        payout.setBankAccountNumber(transport.getBankAccountNumber());
        payout.setBankAccountHolder(transport.getBankAccountHolder());

        TransportPayout savedPayout = payoutRepository.save(payout);

        List<TransportPayoutItem> payoutItems = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (BookingSettlement settlement : readySettlements) {
            TransportPayoutItem item = new TransportPayoutItem();
            item.setPayoutId(savedPayout.getPayoutId());
            item.setSettlementId(settlement.getSettlementId());
            item.setBookingId(settlement.getBookingId());
            item.setAmountVnd(netAmount(settlement));
            payoutItems.add(item);

            settlement.setPayoutId(savedPayout.getPayoutId());
            settlement.setStatus(SettlementStatus.IN_PAYOUT);
            if (settlement.getReadyAt() == null) {
                settlement.setReadyAt(now);
            }
        }

        payoutItemRepository.saveAll(payoutItems);
        settlementRepository.saveAll(readySettlements);

        PayoutDTO result = PayoutDTO.fromEntity(savedPayout);
        result.setItems(payoutItems.stream()
                .map(PayoutItemDTO::fromEntity)
                .collect(Collectors.toList()));

        return result;
    }

    /**
     * Creates payout batches for all transports that have READY settlements.
     *
     * @return list of created payout DTOs
     */
    @Transactional
    public List<PayoutDTO> createPayoutBatchForAllTransports() {
        List<Long> transportIds = settlementRepository.findTransportsWithReadySettlements();

        List<PayoutDTO> createdPayouts = new ArrayList<>();
        for (Long transportId : transportIds) {
            try {
                PayoutDTO payout = createPayoutBatch(transportId);
                createdPayouts.add(payout);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create payout for transport " + transportId, e);
            }
        }

        return createdPayouts;
    }

    /**
     * Automatically sweep payouts for transports that meet the minimum balance threshold.
     * Ready settlements are grouped per transport and turned into a payout batch.
     */
    @Transactional
    public List<PayoutDTO> autoSweepPayouts(long minBalanceVnd) {
        List<Long> transportIds = settlementRepository.findTransportsWithReadySettlements();
        List<PayoutDTO> created = new ArrayList<>();

        for (Long transportId : transportIds) {
            List<BookingSettlement> readySettlements = settlementRepository
                    .findByTransportIdAndStatus(transportId, SettlementStatus.READY)
                    .stream()
                    .filter(settlement -> settlement.getPayoutId() == null)
                    .collect(Collectors.toList());

            if (readySettlements.isEmpty()) {
                continue;
            }

            long totalAmount = readySettlements.stream()
                    .mapToLong(this::netAmount)
                    .sum();

            if (totalAmount < minBalanceVnd) {
                continue;
            }

            try {
                PayoutDTO payout = createPayoutBatch(transportId);
                created.add(payout);
            } catch (Exception ex) {
                log.warn("Auto payout skipped for transport {}: {}", transportId, ex.getMessage());
            }
        }

        return created;
    }

    /**
     * Updates the status of a payout.
     *
     * @param payoutId the payout ID
     * @param status the new status
     * @param failureReason optional failure reason (for FAILED status)
     * @return the updated payout DTO
     * @throws ResourceNotFoundException if payout not found
     */
    @Transactional
    public PayoutDTO updatePayoutStatus(Long payoutId, PayoutStatus status, String failureReason, String transactionReference) {
        TransportPayout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout", "id", payoutId));

        String trimmedReference = transactionReference != null ? transactionReference.trim() : null;
        if (trimmedReference != null && trimmedReference.isEmpty()) {
            trimmedReference = null;
        }
        if (trimmedReference != null) {
            payout.setTransactionReference(trimmedReference);
        }

        String trimmedFailureReason = failureReason != null ? failureReason.trim() : null;
        if (trimmedFailureReason != null && trimmedFailureReason.isEmpty()) {
            trimmedFailureReason = null;
        }

        payout.setStatus(status);

        switch (status) {
            case PROCESSING:
                if (payout.getProcessedAt() == null) {
                    payout.setProcessedAt(LocalDateTime.now());
                }
                payout.setFailureReason(null);
                if (payout.getTransactionReference() == null) {
                    ExternalPayoutResult dispatchResult = dispatchThroughGateway(payout);
                    if (dispatchResult != null) {
                        if (dispatchResult.isSuccess()) {
                            if (dispatchResult.getTransactionReference() != null) {
                                payout.setTransactionReference(dispatchResult.getTransactionReference());
                            }
                        } else {
                            handlePayoutFailure(payout,
                                    dispatchResult.getFailureReason(),
                                    dispatchResult.isRetryable());
                            break;
                        }
                    }
                }
                break;
            case COMPLETED:
                if (payout.getProcessedAt() == null) {
                    payout.setProcessedAt(LocalDateTime.now());
                }
                payout.setCompletedAt(LocalDateTime.now());
                payout.setFailureReason(null);
                processWalletDebit(payout);
                markSettlementsPaid(payoutId);
                break;
            case FAILED:
                handlePayoutFailure(payout, trimmedFailureReason, true);
                break;
            default:
                break;
        }

        TransportPayout savedPayout = payoutRepository.save(payout);
        return PayoutDTO.fromEntity(savedPayout);
    }

    /**
     * Retrieves payout details including all items.
     *
     * @param payoutId the payout ID
     * @return the payout DTO with items
     * @throws ResourceNotFoundException if payout not found
     */
    @Transactional(readOnly = true)
    public PayoutDTO getPayoutDetails(Long payoutId) {
        TransportPayout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout", "id", payoutId));

        List<TransportPayoutItem> items = payoutItemRepository.findByPayoutId(payoutId);

        PayoutDTO result = PayoutDTO.fromEntity(payout);
        result.setItems(items.stream()
                .map(PayoutItemDTO::fromEntity)
                .collect(Collectors.toList()));

        return result;
    }

    /**
     * Retrieves all pending payouts for a specific transport.
     *
     * @param transportId the transport company ID
     * @return list of pending payout DTOs
     */
    @Transactional(readOnly = true)
    public List<PayoutDTO> getPendingPayoutsByTransport(Long transportId) {
        List<TransportPayout> payouts = payoutRepository.findByTransportIdOrderByCreatedAtDesc(transportId);

        return payouts.stream()
                .filter(p -> p.getStatus() == PayoutStatus.PENDING || p.getStatus() == PayoutStatus.PROCESSING)
                .map(PayoutDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves all payouts by status.
     *
     * @param status the payout status
     * @return list of payout DTOs
     */
    @Transactional(readOnly = true)
    public List<PayoutDTO> getPayoutsByStatus(PayoutStatus status) {
        List<TransportPayout> payouts = payoutRepository.findByStatus(status);
        return payouts.stream()
                .map(PayoutDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayoutDTO> getAllPayouts() {
        List<TransportPayout> payouts = payoutRepository.findAll();
        return payouts.stream()
                .map(PayoutDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayoutDTO> getPayoutsByTransport(Long transportId) {
        List<TransportPayout> payouts = payoutRepository.findByTransportIdOrderByCreatedAtDesc(transportId);
        return payouts.stream()
                .map(PayoutDTO::fromEntity)
                .collect(Collectors.toList());
    }

    private long netAmount(BookingSettlement settlement) {
        if (settlement == null) {
            return 0L;
        }
        Long net = settlement.getNetToTransportVnd();
        return net != null ? net : 0L;
    }

    private String generatePayoutNumber(Long transportId) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String payoutNumber = String.format("PO-%d-%s", transportId, timestamp);

        int counter = 1;
        while (payoutRepository.existsByPayoutNumber(payoutNumber)) {
            payoutNumber = String.format("PO-%d-%s-%d", transportId, timestamp, counter++);
        }

        return payoutNumber;
    }

    /**
     * Rolls back settlements to READY status when a payout fails.
     *
     * @param payoutId the failed payout ID
     */
    private void rollbackSettlements(Long payoutId, boolean placeOnHold, String holdReason) {
        List<TransportPayoutItem> items = payoutItemRepository.findByPayoutId(payoutId);

        for (TransportPayoutItem item : items) {
            settlementRepository.findById(item.getSettlementId()).ifPresent(settlement -> {
                if (placeOnHold) {
                    settlement.setStatus(SettlementStatus.ON_HOLD);
                    String reason = (holdReason != null && !holdReason.trim().isEmpty())
                            ? holdReason.trim()
                            : "Payout failure requires manual review";
                    settlement.setOnHoldReason(reason);
                } else {
                    settlement.setStatus(SettlementStatus.READY);
                    settlement.setOnHoldReason(null);
                }
                settlement.setPayoutId(null);
                settlement.setPaidAt(null);
                settlementRepository.save(settlement);
            });
        }
    }

    private void markSettlementsPaid(Long payoutId) {
        List<TransportPayoutItem> items = payoutItemRepository.findByPayoutId(payoutId);
        LocalDateTime now = LocalDateTime.now();

        for (TransportPayoutItem item : items) {
            settlementRepository.findById(item.getSettlementId()).ifPresent(settlement -> {
                settlement.setStatus(SettlementStatus.PAID);
                settlement.setPaidAt(now);
                settlement.setPayoutId(payoutId);
                settlementRepository.save(settlement);
            });
        }
    }

    private ExternalPayoutResult dispatchThroughGateway(TransportPayout payout) {
        if (externalPayoutGateway == null) {
            log.warn("External payout gateway not configured; payout {} will remain PROCESSING", payout.getPayoutId());
            return null;
        }
        List<TransportPayoutItem> items = payoutItemRepository.findByPayoutId(payout.getPayoutId());
        try {
            return externalPayoutGateway.dispatchPayoutBatch(payout, items);
        } catch (Exception ex) {
            log.error("Gateway dispatch failed for payout {}: {}", payout.getPayoutId(), ex.getMessage(), ex);
            return ExternalPayoutResult.failure("Gateway exception: " + ex.getMessage(), true);
        }
    }

    private void handlePayoutFailure(TransportPayout payout, String failureReason, boolean retryable) {
        String reason = failureReason != null ? failureReason.trim() : null;
        if (reason == null || reason.isEmpty()) {
            reason = "Payout failed";
        }
        log.error("Payout {} failed (retryable={}): {}", payout.getPayoutId(), retryable, reason);
        payout.setStatus(PayoutStatus.FAILED);
        if (payout.getProcessedAt() == null) {
            payout.setProcessedAt(LocalDateTime.now());
        }
        payout.setCompletedAt(LocalDateTime.now());
        payout.setFailureReason(reason);
        boolean placeOnHold = !retryable;
        rollbackSettlements(payout.getPayoutId(), placeOnHold, reason);
        walletService.reversePayoutIfNeeded(payout.getTransportId(), payout.getPayoutId(), payout.getTotalAmountVnd());
    }

    private void processWalletDebit(TransportPayout payout) {
        Long amount = payout.getTotalAmountVnd();
        if (amount == null || amount <= 0) {
            return;
        }

        boolean alreadyDebited = walletService.hasReferenceTransaction(
                WalletTransactionReferenceType.PAYOUT,
                payout.getPayoutId(),
                WalletTransactionType.PAYOUT_DEBIT);

        if (alreadyDebited) {
            return;
        }

        TransportWallet wallet = walletService.getOrCreateWallet(payout.getTransportId());
        walletService.debitWallet(wallet.getWalletId(), amount,
                WalletTransactionType.PAYOUT_DEBIT,
                WalletTransactionReferenceType.PAYOUT,
                payout.getPayoutId(),
                "Payout " + payout.getPayoutNumber(),
                null);
    }
}

