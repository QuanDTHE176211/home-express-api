package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.response.AdminDashboardStatsResponse;
import com.homeexpress.home_express_api.dto.response.AdminDashboardStatsResponse.TopTransportSummary;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class DashboardService {

    private final UserRepository userRepository;

    private final TransportRepository transportRepository;

    public AdminDashboardStatsResponse getAdminDashboardStats() {
        AdminDashboardStatsResponse stats = new AdminDashboardStatsResponse();

        long totalUsers = userRepository.count();
        long totalCustomers = userRepository.countByRole(UserRole.CUSTOMER);
        long totalTransports = userRepository.countByRole(UserRole.TRANSPORT);
        long totalManagers = userRepository.countByRole(UserRole.MANAGER);
        long activeUsers = userRepository.countByIsActive(true);
        long verifiedUsers = userRepository.countByIsVerified(true);

        LocalDate today = LocalDate.now();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime startOfTomorrow = startOfToday.plusDays(1);

        LocalDate startOfWeek = today.with(DayOfWeek.MONDAY);
        LocalDateTime startOfWeekDateTime = startOfWeek.atStartOfDay();

        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDateTime startOfMonthDateTime = startOfMonth.atStartOfDay();

        LocalDate previousMonthStart = startOfMonth.minusMonths(1);
        LocalDateTime previousMonthStartDateTime = previousMonthStart.atStartOfDay();
        LocalDateTime previousMonthEndDateTime = startOfMonthDateTime;

        long newUsersToday = userRepository.countByCreatedAtBetween(startOfToday, startOfTomorrow);
        long newUsersWeek = userRepository.countByCreatedAtBetween(startOfWeekDateTime, startOfTomorrow);
        long newUsersMonth = userRepository.countByCreatedAtBetween(startOfMonthDateTime, startOfTomorrow);
        long newUsersPreviousMonth = userRepository.countByCreatedAtBetween(previousMonthStartDateTime, previousMonthEndDateTime);

        double growthRate = calculateGrowthRate(newUsersMonth, newUsersPreviousMonth);
        
        long verifiedTransports = transportRepository.countByVerificationStatus(VerificationStatus.APPROVED);

        long pendingTransportVerifications = transportRepository.countByVerificationStatus(VerificationStatus.PENDING);

        List<TopTransportSummary> topTransports = transportRepository
                .findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus.APPROVED)
                .stream()
                .limit(5)
                .map(this::mapTopTransport)
                .collect(Collectors.toList());

        stats.setTotalUsers(totalUsers);
        stats.setTotalCustomers(totalCustomers);
        stats.setTotalTransports(totalTransports);
        stats.setTotalManagers(totalManagers);
        stats.setActiveUsers(activeUsers);
        stats.setInactiveUsers(totalUsers - activeUsers);
        stats.setVerifiedUsers(verifiedUsers);
        stats.setVerifiedTransports(verifiedTransports);
        stats.setNewUsersToday(newUsersToday);
        stats.setNewUsersThisWeek(newUsersWeek);
        stats.setNewUsersThisMonth(newUsersMonth);
        stats.setUserGrowthRate(String.format("%.1f%%", growthRate));
        stats.setPendingTransportVerifications(pendingTransportVerifications);
        stats.setTopTransports(topTransports);

        return stats;
    }

    public Map<String, Object> getPlatformStatistics() {
        AdminDashboardStatsResponse response = getAdminDashboardStats();
        Map<String, Object> map = new HashMap<>();
        map.put("totalUsers", response.getTotalUsers());
        map.put("totalCustomers", response.getTotalCustomers());
        map.put("totalTransports", response.getTotalTransports());
        map.put("totalManagers", response.getTotalManagers());
        map.put("activeUsers", response.getActiveUsers());
        map.put("verifiedUsers", response.getVerifiedUsers());
        map.put("verifiedTransports", response.getVerifiedTransports());
        map.put("inactiveUsers", response.getInactiveUsers());
        map.put("newUsersToday", response.getNewUsersToday());
        map.put("newUsersThisWeek", response.getNewUsersThisWeek());
        map.put("newUsersThisMonth", response.getNewUsersThisMonth());
        map.put("userGrowthRate", response.getUserGrowthRate());
        map.put("pendingTransportVerifications", response.getPendingTransportVerifications());
        map.put("topTransports", response.getTopTransports());
        return map;
    }

    private double calculateGrowthRate(long current, long previous) {
        if (previous == 0) {
            return current > 0 ? 100.0 : 0.0;
        }
        return ((double) (current - previous) / previous) * 100.0;
    }

    private TopTransportSummary mapTopTransport(Transport transport) {
        TopTransportSummary summary = new TopTransportSummary();
        summary.setTransportId(transport.getTransportId());
        summary.setCompanyName(transport.getCompanyName());
        BigDecimal averageRating = transport.getAverageRating();
        summary.setAverageRating(averageRating != null ? averageRating.doubleValue() : 0.0);
        summary.setCompletedBookings(transport.getCompletedBookings());
        return summary;
    }
}


