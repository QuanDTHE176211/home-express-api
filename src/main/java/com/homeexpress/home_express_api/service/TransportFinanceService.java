package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.transport.TransportEarningsStatsResponse;
import com.homeexpress.home_express_api.dto.transport.TransportTransactionDto;
import com.homeexpress.home_express_api.dto.transport.TransportWalletReportResponse;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingSettlement;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.Customer;
import com.homeexpress.home_express_api.entity.PayoutStatus;
import com.homeexpress.home_express_api.entity.SettlementStatus;
import com.homeexpress.home_express_api.entity.TransportPayout;
import com.homeexpress.home_express_api.entity.TransportWallet;
import com.homeexpress.home_express_api.entity.TransportWalletTransaction;
import com.homeexpress.home_express_api.entity.WalletTransactionReferenceType;
import com.homeexpress.home_express_api.entity.WalletTransactionType;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.BookingSettlementRepository;
import com.homeexpress.home_express_api.repository.CustomerRepository;
import com.homeexpress.home_express_api.repository.TransportPayoutRepository;
import com.homeexpress.home_express_api.repository.TransportWalletTransactionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransportFinanceService {

    private static final int MONTH_WINDOW = 6;
    private static final int DEFAULT_REPORT_DAYS = 30;
    private static final int MAX_REPORT_DAYS = 90;

    private final BookingRepository bookingRepository;
    private final BookingSettlementRepository bookingSettlementRepository;
    private final CustomerRepository customerRepository;
    private final TransportPayoutRepository transportPayoutRepository;
    private final TransportWalletTransactionRepository walletTransactionRepository;
    private final WalletService walletService;

    public TransportFinanceService(BookingRepository bookingRepository,
            BookingSettlementRepository bookingSettlementRepository,
            CustomerRepository customerRepository,
            TransportPayoutRepository transportPayoutRepository,
            TransportWalletTransactionRepository walletTransactionRepository,
            WalletService walletService) {
        this.bookingRepository = bookingRepository;
        this.bookingSettlementRepository = bookingSettlementRepository;
        this.customerRepository = customerRepository;
        this.transportPayoutRepository = transportPayoutRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.walletService = walletService;
    }

    @Transactional
    public TransportEarningsStatsResponse getEarningsStats(Long transportId) {
        TransportWallet wallet = walletService.getOrCreateWallet(transportId);
        TransportEarningsStatsResponse response = new TransportEarningsStatsResponse();

        long totalBookings = bookingRepository.countByTransportIdAndStatus(transportId, BookingStatus.COMPLETED);
        response.setTotalBookings(totalBookings);
        response.setTotalEarnings(wallet.getTotalEarnedVnd());
        response.setCurrentBalance(wallet.getCurrentBalanceVnd());

        YearMonth currentMonth = YearMonth.now();
        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = currentMonth.plusMonths(1).atDay(1).atStartOfDay();
        List<TransportWalletTransaction> monthLedger = walletTransactionRepository
                .findByWallet_WalletIdAndCreatedAtBetweenOrderByCreatedAtAsc(wallet.getWalletId(), monthStart, nextMonthStart);

        long thisMonthRevenue = monthLedger.stream()
                .filter(tx -> isCredit(tx.getTransactionType()))
                .mapToLong(TransportWalletTransaction::getAmount)
                .sum();
        response.setThisMonthEarnings(thisMonthRevenue);

        List<Booking> completedThisMonth = bookingRepository.findByTransportIdAndStatusAndActualEndTimeBetween(
                transportId,
                BookingStatus.COMPLETED,
                monthStart,
                nextMonthStart
        );
        response.setThisMonthBookings(completedThisMonth.size());

        response.setAveragePerBooking(totalBookings > 0 ? wallet.getTotalEarnedVnd() / totalBookings : 0L);

        List<BookingSettlement> settlements = bookingSettlementRepository.findByTransportId(transportId);
        EnumSet<SettlementStatus> pendingStatuses = EnumSet.of(
                SettlementStatus.PENDING,
                SettlementStatus.READY,
                SettlementStatus.IN_PAYOUT,
                SettlementStatus.ON_HOLD
        );

        long pendingAmount = settlements.stream()
                .filter(s -> pendingStatuses.contains(s.getStatus()))
                .map(BookingSettlement::getNetToTransportVnd)
                .filter(amount -> amount != null)
                .mapToLong(Long::longValue)
                .sum();
        response.setPendingAmount(pendingAmount);

        int pendingTransactions = (int) settlements.stream()
                .filter(s -> pendingStatuses.contains(s.getStatus()))
                .count();
        response.setPendingTransactions(pendingTransactions);

        response.setGrowthRate("0%");
        response.setMonthlyBreakdown(buildMonthlyBreakdown(transportId, wallet));

        return response;
    }

    @Transactional
    public List<TransportTransactionDto> getTransactions(Long transportId) {
        TransportWallet wallet = walletService.getOrCreateWallet(transportId);
        List<TransportWalletTransaction> ledger = walletTransactionRepository
                .findByWallet_WalletIdOrderByCreatedAtDesc(wallet.getWalletId());
        LedgerReferenceContext context = buildLedgerReferenceContext(ledger);

        return ledger.stream()
                .map(tx -> toTransactionDto(tx, context))
                .collect(Collectors.toList());
    }

    @Transactional
    public TransportWalletReportResponse getWalletReport(Long transportId, Integer requestedDays) {
        int windowDays = resolveReportDays(requestedDays);
        TransportWallet wallet = walletService.getOrCreateWallet(transportId);

        TransportWalletReportResponse report = new TransportWalletReportResponse();
        TransportWalletReportResponse.WalletSnapshot snapshot = report.getSnapshot();
        snapshot.setCurrentBalanceVnd(wallet.getCurrentBalanceVnd());
        snapshot.setTotalEarnedVnd(wallet.getTotalEarnedVnd());
        snapshot.setTotalWithdrawnVnd(wallet.getTotalWithdrawnVnd());
        snapshot.setLastTransactionAt(wallet.getLastTransactionAt());

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(windowDays - 1L);
        LocalDateTime rangeStart = startDate.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();

        List<TransportWalletTransaction> rangeTransactions = walletTransactionRepository
                .findByWallet_WalletIdAndCreatedAtBetweenOrderByCreatedAtAsc(wallet.getWalletId(), rangeStart, rangeEnd);
        LedgerReferenceContext context = buildLedgerReferenceContext(rangeTransactions);

        report.setCashflow(buildCashflowEntries(rangeTransactions, context));
        report.setDailyBalances(buildDailyBalance(rangeTransactions, wallet, rangeStart, startDate, today));
        report.setReconciliation(buildReconciliation(transportId, wallet));

        return report;
    }

    private List<TransportEarningsStatsResponse.MonthlyBreakdown> buildMonthlyBreakdown(Long transportId, TransportWallet wallet) {
        List<TransportEarningsStatsResponse.MonthlyBreakdown> breakdowns = new ArrayList<>();

        YearMonth end = YearMonth.now();
        YearMonth start = end.minusMonths(MONTH_WINDOW - 1);

        LocalDateTime rangeStart = start.atDay(1).atStartOfDay();
        LocalDateTime rangeEnd = end.plusMonths(1).atDay(1).atStartOfDay();

        List<TransportWalletTransaction> ledger = walletTransactionRepository
                .findByWallet_WalletIdAndCreatedAtBetweenOrderByCreatedAtAsc(wallet.getWalletId(), rangeStart, rangeEnd);

        Map<YearMonth, Long> revenueByMonth = ledger.stream()
                .filter(tx -> isCredit(tx.getTransactionType()))
                .collect(Collectors.groupingBy(tx -> YearMonth.from(tx.getCreatedAt()),
                        Collectors.summingLong(TransportWalletTransaction::getAmount)));

        YearMonth current = start;
        while (!current.isAfter(end)) {
            LocalDateTime monthStart = current.atDay(1).atStartOfDay();
            LocalDateTime monthEnd = current.plusMonths(1).atDay(1).atStartOfDay();

            List<Booking> bookings = bookingRepository.findByTransportIdAndStatusAndActualEndTimeBetween(
                    transportId,
                    BookingStatus.COMPLETED,
                    monthStart,
                    monthEnd
            );

            TransportEarningsStatsResponse.MonthlyBreakdown breakdown = new TransportEarningsStatsResponse.MonthlyBreakdown();
            breakdown.setMonth(current.toString());
            breakdown.setRevenue(revenueByMonth.getOrDefault(current, 0L));
            breakdown.setBookings(bookings.size());
            breakdowns.add(breakdown);

            current = current.plusMonths(1);
        }

        return breakdowns;
    }

    private List<TransportWalletReportResponse.CashflowEntry> buildCashflowEntries(
            List<TransportWalletTransaction> transactions,
            LedgerReferenceContext context) {

        return transactions.stream()
                .map(tx -> {
                    TransportWalletReportResponse.CashflowEntry entry = new TransportWalletReportResponse.CashflowEntry();
                    entry.setTransactionId(tx.getTransactionId());
                    entry.setTransactionType(tx.getTransactionType());
                    entry.setReferenceType(tx.getReferenceType());
                    entry.setReferenceId(tx.getReferenceId());
                    entry.setAmountVnd(tx.getAmount());
                    entry.setRunningBalanceVnd(tx.getRunningBalanceVnd());
                    entry.setInflow(isCredit(tx.getTransactionType()));
                    entry.setCreatedAt(tx.getCreatedAt());

                    if (tx.getReferenceType() == WalletTransactionReferenceType.SETTLEMENT && tx.getReferenceId() != null) {
                        BookingSettlement settlement = context.settlements.get(tx.getReferenceId());
                        if (settlement != null) {
                            entry.setBookingId(settlement.getBookingId());
                        }
                    } else if (tx.getReferenceType() == WalletTransactionReferenceType.PAYOUT && tx.getReferenceId() != null) {
                        entry.setPayoutId(tx.getReferenceId());
                    }
                    return entry;
                })
                .collect(Collectors.toList());
    }

    private List<TransportWalletReportResponse.DailyBalancePoint> buildDailyBalance(
            List<TransportWalletTransaction> transactions,
            TransportWallet wallet,
            LocalDateTime rangeStart,
            LocalDate startDate,
            LocalDate endDate) {

        long baseline = walletTransactionRepository
                .findTopByWallet_WalletIdAndCreatedAtBeforeOrderByCreatedAtDesc(wallet.getWalletId(), rangeStart)
                .map(TransportWalletTransaction::getRunningBalanceVnd)
                .orElse(0L);

        List<TransportWalletReportResponse.DailyBalancePoint> points = new ArrayList<>();
        int index = 0;
        long closingBalance = baseline;

        for (LocalDate cursor = startDate; !cursor.isAfter(endDate); cursor = cursor.plusDays(1)) {
            LocalDateTime dayEnd = cursor.plusDays(1).atStartOfDay();
            while (index < transactions.size()
                    && (transactions.get(index).getCreatedAt().isBefore(dayEnd))) {
                closingBalance = transactions.get(index).getRunningBalanceVnd();
                index++;
            }
            TransportWalletReportResponse.DailyBalancePoint point = new TransportWalletReportResponse.DailyBalancePoint();
            point.setDate(cursor.toString());
            point.setClosingBalanceVnd(closingBalance);
            points.add(point);
        }
        return points;
    }

    private TransportWalletReportResponse.ReconciliationReport buildReconciliation(Long transportId, TransportWallet wallet) {
        TransportWalletReportResponse.ReconciliationReport report = new TransportWalletReportResponse.ReconciliationReport();

        List<BookingSettlement> settlements = bookingSettlementRepository.findByTransportId(transportId);
        List<Long> expectedSettlementIds = settlements.stream()
                .filter(s -> EnumSet.of(SettlementStatus.READY, SettlementStatus.IN_PAYOUT, SettlementStatus.PAID, SettlementStatus.ON_HOLD)
                        .contains(s.getStatus()))
                .map(BookingSettlement::getSettlementId)
                .collect(Collectors.toList());
        report.setSettlementCount(expectedSettlementIds.size());

        Set<Long> ledgerSettlementIds = walletTransactionRepository
                .findByWallet_WalletIdAndReferenceTypeOrderByCreatedAtAsc(wallet.getWalletId(), WalletTransactionReferenceType.SETTLEMENT)
                .stream()
                .map(TransportWalletTransaction::getReferenceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        report.setWalletSettlementCount(ledgerSettlementIds.size());

        List<Long> missingSettlements = expectedSettlementIds.stream()
                .filter(id -> !ledgerSettlementIds.contains(id))
                .collect(Collectors.toList());
        report.setMissingSettlementIds(missingSettlements);

        List<Long> completedPayoutIds = transportPayoutRepository.findByTransportId(transportId).stream()
                .filter(payout -> payout.getStatus() == PayoutStatus.COMPLETED)
                .map(TransportPayout::getPayoutId)
                .collect(Collectors.toList());
        report.setPayoutCount(completedPayoutIds.size());

        Set<Long> ledgerPayoutIds = walletTransactionRepository
                .findByWallet_WalletIdAndReferenceTypeOrderByCreatedAtAsc(wallet.getWalletId(), WalletTransactionReferenceType.PAYOUT)
                .stream()
                .map(TransportWalletTransaction::getReferenceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        report.setWalletPayoutCount(ledgerPayoutIds.size());

        List<Long> missingPayouts = completedPayoutIds.stream()
                .filter(id -> !ledgerPayoutIds.contains(id))
                .collect(Collectors.toList());
        report.setMissingPayoutIds(missingPayouts);

        report.setBalanced(missingSettlements.isEmpty() && missingPayouts.isEmpty());
        return report;
    }

    private LedgerReferenceContext buildLedgerReferenceContext(List<TransportWalletTransaction> transactions) {
        LedgerReferenceContext context = new LedgerReferenceContext();
        if (transactions.isEmpty()) {
            return context;
        }

        Set<Long> settlementIds = transactions.stream()
                .filter(tx -> tx.getReferenceType() == WalletTransactionReferenceType.SETTLEMENT)
                .map(TransportWalletTransaction::getReferenceId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, BookingSettlement> settlements = settlementIds.isEmpty()
                ? Collections.emptyMap()
                : bookingSettlementRepository.findAllById(settlementIds).stream()
                        .collect(Collectors.toMap(BookingSettlement::getSettlementId, Function.identity()));

        Set<Long> bookingIds = settlements.values().stream()
                .map(BookingSettlement::getBookingId)
                .collect(Collectors.toSet());

        Map<Long, Booking> bookings = bookingIds.isEmpty()
                ? Collections.emptyMap()
                : bookingRepository.findAllById(bookingIds).stream()
                        .collect(Collectors.toMap(Booking::getBookingId, Function.identity()));

        Set<Long> customerIds = bookings.values().stream()
                .map(Booking::getCustomerId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, Customer> customers = customerIds.isEmpty()
                ? Collections.emptyMap()
                : customerRepository.findAllById(customerIds).stream()
                        .collect(Collectors.toMap(Customer::getCustomerId, Function.identity()));

        Set<Long> payoutIds = transactions.stream()
                .filter(tx -> tx.getReferenceType() == WalletTransactionReferenceType.PAYOUT)
                .map(TransportWalletTransaction::getReferenceId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, TransportPayout> payouts = payoutIds.isEmpty()
                ? Collections.emptyMap()
                : transportPayoutRepository.findAllById(payoutIds).stream()
                        .collect(Collectors.toMap(TransportPayout::getPayoutId, Function.identity()));

        context.settlements = settlements;
        context.bookings = bookings;
        context.customers = customers;
        context.payouts = payouts;
        return context;
    }

    private TransportTransactionDto toTransactionDto(TransportWalletTransaction tx, LedgerReferenceContext context) {
        TransportTransactionDto dto = new TransportTransactionDto();
        dto.setTransactionId("WTX-" + tx.getTransactionId());
        dto.setAmount(isCredit(tx.getTransactionType()) ? tx.getAmount() : -tx.getAmount());
        dto.setStatus(tx.getTransactionType() != null ? tx.getTransactionType().name() : null);
        dto.setPaymentMethod(tx.getReferenceType() != null ? tx.getReferenceType().name() : null);
        dto.setCreatedAt(formatDateTime(tx.getCreatedAt()));
        dto.setExpectedDate(null);

        if (tx.getReferenceType() == WalletTransactionReferenceType.SETTLEMENT && tx.getReferenceId() != null) {
            BookingSettlement settlement = context.settlements.get(tx.getReferenceId());
            if (settlement != null) {
                dto.setBookingId(settlement.getBookingId());
                Booking booking = context.bookings.get(settlement.getBookingId());
                if (booking != null) {
                    Customer customer = booking.getCustomerId() != null ? context.customers.get(booking.getCustomerId()) : null;
                    dto.setCustomerName(customer != null ? customer.getFullName() : null);
                }
            }
        } else if (tx.getReferenceType() == WalletTransactionReferenceType.PAYOUT && tx.getReferenceId() != null) {
            TransportPayout payout = context.payouts.get(tx.getReferenceId());
            if (payout != null) {
                dto.setCustomerName("Payout " + payout.getPayoutNumber());
            }
        }
        return dto;
    }

    private boolean isCredit(WalletTransactionType type) {
        if (type == null) {
            return true;
        }
        return type == WalletTransactionType.SETTLEMENT_CREDIT
                || type == WalletTransactionType.ADJUSTMENT_CREDIT
                || type == WalletTransactionType.REVERSAL;
    }

    private int resolveReportDays(Integer requestedDays) {
        if (requestedDays == null || requestedDays <= 0) {
            return DEFAULT_REPORT_DAYS;
        }
        return Math.min(requestedDays, MAX_REPORT_DAYS);
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null
                ? dateTime.atZone(ZoneOffset.systemDefault()).toInstant().toString()
                : null;
    }

    private static class LedgerReferenceContext {
        private Map<Long, BookingSettlement> settlements = new HashMap<>();
        private Map<Long, Booking> bookings = new HashMap<>();
        private Map<Long, Customer> customers = new HashMap<>();
        private Map<Long, TransportPayout> payouts = new HashMap<>();
    }
}
