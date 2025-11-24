package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.response.TransportDashboardStatsResponse;
import com.homeexpress.home_express_api.dto.response.TransportDashboardStatsResponse.MonthlyRevenuePoint;
import com.homeexpress.home_express_api.dto.response.TransportQuotationSummaryResponse;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.Customer;
import com.homeexpress.home_express_api.entity.Quotation;
import com.homeexpress.home_express_api.entity.QuotationStatus;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.CustomerRepository;
import com.homeexpress.home_express_api.repository.QuotationRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class TransportDashboardService {

    private static final int DEFAULT_MONTH_WINDOW = 6;

    private final BookingRepository bookingRepository;

    private final QuotationRepository quotationRepository;

    private final CustomerRepository customerRepository;
    
    private final TransportRepository transportRepository;

    @Transactional(readOnly = true)
    public TransportDashboardStatsResponse getDashboardStats(Long transportId) {
        TransportDashboardStatsResponse response = new TransportDashboardStatsResponse();

        long totalBookings = bookingRepository.countByTransportId(transportId);
        long completedBookings = bookingRepository.countByTransportIdAndStatus(transportId, BookingStatus.COMPLETED);
        long inProgressBookings = countActiveBookings(transportId);
        long pendingQuotations = quotationRepository.countByTransportIdAndStatus(
                transportId, QuotationStatus.PENDING);

        BigDecimal totalIncome = defaultZero(
                bookingRepository.sumFinalPriceByTransportAndStatus(transportId, BookingStatus.COMPLETED));

        // Get average rating from Transport entity (managed separately from review system)
        BigDecimal averageRating = transportRepository.findById(transportId)
                .map(t -> t.getAverageRating() != null ? t.getAverageRating() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
        double completionRate = totalBookings == 0
                ? 0d
                : roundTwoDecimal((completedBookings * 100.0) / totalBookings);

        response.setTotalIncome(totalIncome.doubleValue());
        response.setTotalBookings(totalBookings);
        response.setCompletedBookings(completedBookings);
        response.setInProgressBookings(inProgressBookings);
        response.setAverageRating(roundTwoDecimal(averageRating.doubleValue()));
        response.setCompletionRate(completionRate);
        response.setPendingQuotations(pendingQuotations);
        response.setMonthlyRevenue(buildMonthlyRevenueSeries(transportId));

        return response;
    }

    @Transactional(readOnly = true)
    public List<TransportQuotationSummaryResponse> getRecentQuotations(Long transportId, int limit) {
        PageRequest pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Quotation> page = quotationRepository.findByTransportId(transportId, pageable);
        if (page.isEmpty()) {
            return List.of();
        }

        Map<Long, Booking> bookingsById = loadBookings(page.stream()
                .map(Quotation::getBookingId)
                .collect(Collectors.toSet()));

        // Load all quotations for these bookings to calculate competitors and rankings
        Set<Long> bookingIds = page.stream()
            .map(Quotation::getBookingId)
            .collect(Collectors.toSet());

        Map<Long, List<Quotation>> allQuotationsByBooking = new LinkedHashMap<>();
        if (!bookingIds.isEmpty()) {
            List<Quotation> allQuotations = quotationRepository.findByBookingIdIn(bookingIds);
            for (Quotation q : allQuotations) {
                allQuotationsByBooking.computeIfAbsent(q.getBookingId(), k -> new ArrayList<>()).add(q);
            }
        }

        List<TransportQuotationSummaryResponse> summaries = new ArrayList<>();
        for (Quotation quotation : page) {
            Booking booking = bookingsById.getOrDefault(quotation.getBookingId(), null);

            TransportQuotationSummaryResponse dto = new TransportQuotationSummaryResponse();
            dto.setQuotationId(quotation.getQuotationId());
            dto.setBookingId(quotation.getBookingId());
            dto.setMyQuotePrice(defaultZero(quotation.getQuotedPrice()).doubleValue());
            dto.setStatus(quotation.getStatus().name());
            dto.setExpiresAt(quotation.getExpiresAt());
            dto.setSubmittedAt(quotation.getCreatedAt());

            if (booking != null) {
                dto.setPickupLocation(booking.getPickupAddress());
                dto.setDeliveryLocation(booking.getDeliveryAddress());
                dto.setPreferredDate(booking.getPreferredDate().toString());
            }

            // Calculate competitor information
            List<Quotation> bookingQuotations = allQuotationsByBooking.getOrDefault(quotation.getBookingId(), List.of());
            dto.setCompetitorQuotesCount(bookingQuotations.size() - 1); // Exclude this quotation

            // Calculate lowest competitor price and ranking
            List<Quotation> sortedCompetitors = bookingQuotations.stream()
                .filter(q -> !q.getQuotationId().equals(quotation.getQuotationId()))
                .sorted((a, b) -> defaultZero(a.getQuotedPrice()).compareTo(defaultZero(b.getQuotedPrice())))
                .collect(Collectors.toList());

            if (!sortedCompetitors.isEmpty()) {
                dto.setLowestCompetitorPrice(defaultZero(sortedCompetitors.get(0).getQuotedPrice()).doubleValue());

                // Calculate rank (1-based, lower price = better rank)
                List<BigDecimal> allPrices = bookingQuotations.stream()
                    .map(q -> defaultZero(q.getQuotedPrice()))
                    .sorted()
                    .collect(Collectors.toList());

                BigDecimal myPrice = defaultZero(quotation.getQuotedPrice());
                int rank = 1;
                for (BigDecimal price : allPrices) {
                    if (myPrice.compareTo(price) <= 0) {
                        break;
                    }
                    rank++;
                }
                dto.setMyRank(rank);
            } else {
                dto.setLowestCompetitorPrice(null);
                dto.setMyRank(1);
            }

            summaries.add(dto);
        }

        return summaries;
    }

    private Map<Long, Booking> loadBookings(Set<Long> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return bookingRepository.findAllById(bookingIds).stream()
                .collect(Collectors.toMap(Booking::getBookingId, b -> b));
    }

    private Map<Long, Customer> loadCustomers(Set<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getCustomerId, c -> c));
    }

    private long countActiveBookings(Long transportId) {
        EnumSet<BookingStatus> activeStatuses = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS);
        return activeStatuses.stream()
                .mapToLong(status -> bookingRepository.countByTransportIdAndStatus(transportId, status))
                .sum();
    }

    private List<MonthlyRevenuePoint> buildMonthlyRevenueSeries(Long transportId) {
        LocalDate today = LocalDate.now();
        YearMonth endMonth = YearMonth.from(today);
        YearMonth startMonth = endMonth.minusMonths(DEFAULT_MONTH_WINDOW - 1);

        Map<YearMonth, BigDecimal> revenueByMonth = new LinkedHashMap<>();
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            revenueByMonth.put(current, BigDecimal.ZERO);
            current = current.plusMonths(1);
        }

        LocalDateTime rangeStart = startMonth.atDay(1).atStartOfDay();
        LocalDateTime rangeEnd = endMonth.plusMonths(1).atDay(1).atStartOfDay();

        List<Booking> completedBookings = bookingRepository
                .findByTransportIdAndStatusAndActualEndTimeBetween(
                        transportId,
                        BookingStatus.COMPLETED,
                        rangeStart,
                        rangeEnd
                );

        for (Booking booking : completedBookings) {
            LocalDateTime completedAt = Optional.ofNullable(booking.getActualEndTime())
                    .orElseGet(booking::getUpdatedAt);
            if (completedAt == null) {
                completedAt = booking.getCreatedAt();
            }
            YearMonth key = YearMonth.from(completedAt);
            if (revenueByMonth.containsKey(key)) {
                BigDecimal currentTotal = revenueByMonth.get(key);
                revenueByMonth.put(key, currentTotal.add(defaultZero(booking.getFinalPrice())));
            }
        }

        List<MonthlyRevenuePoint> series = new ArrayList<>();
        for (Map.Entry<YearMonth, BigDecimal> entry : revenueByMonth.entrySet()) {
            String monthLabel = entry.getKey().toString();
            double revenue = roundTwoDecimal(entry.getValue().doubleValue());
            series.add(new MonthlyRevenuePoint(monthLabel, revenue));
        }

        return series;
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private double roundTwoDecimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}


