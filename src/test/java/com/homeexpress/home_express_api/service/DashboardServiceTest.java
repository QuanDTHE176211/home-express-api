package com.homeexpress.home_express_api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homeexpress.home_express_api.dto.response.AdminDashboardStatsResponse;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransportRepository transportRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private List<Transport> topTransports;

    @BeforeEach
    void setUp() {
        Transport transport1 = new Transport();
        transport1.setTransportId(1L);
        transport1.setCompanyName("Express Transport");
        transport1.setAverageRating(BigDecimal.valueOf(4.8));
        transport1.setCompletedBookings(150);

        Transport transport2 = new Transport();
        transport2.setTransportId(2L);
        transport2.setCompanyName("Fast Movers");
        transport2.setAverageRating(BigDecimal.valueOf(4.5));
        transport2.setCompletedBookings(120);

        topTransports = new ArrayList<>();
        topTransports.add(transport1);
        topTransports.add(transport2);
    }

    @Test
    void getAdminDashboardStats_Success() {
        when(userRepository.count()).thenReturn(1000L);
        when(userRepository.countByRole(UserRole.CUSTOMER)).thenReturn(800L);
        when(userRepository.countByRole(UserRole.TRANSPORT)).thenReturn(150L);
        when(userRepository.countByRole(UserRole.MANAGER)).thenReturn(50L);
        when(userRepository.countByIsActive(true)).thenReturn(900L);
        when(userRepository.countByIsVerified(true)).thenReturn(850L);
        when(userRepository.countByCreatedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(10L)
                .thenReturn(50L)
                .thenReturn(100L)
                .thenReturn(80L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.PENDING)).thenReturn(15L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.APPROVED)).thenReturn(120L);
        when(transportRepository.findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus.APPROVED))
                .thenReturn(topTransports);

        AdminDashboardStatsResponse result = dashboardService.getAdminDashboardStats();

        assertNotNull(result);
        assertEquals(1000L, result.getTotalUsers());
        assertEquals(800L, result.getTotalCustomers());
        assertEquals(150L, result.getTotalTransports());
        assertEquals(50L, result.getTotalManagers());
        assertEquals(900L, result.getActiveUsers());
        assertEquals(100L, result.getInactiveUsers());
        assertEquals(850L, result.getVerifiedUsers());
        assertEquals(120L, result.getVerifiedTransports());
        assertEquals(10L, result.getNewUsersToday());
        assertEquals(50L, result.getNewUsersThisWeek());
        assertEquals(100L, result.getNewUsersThisMonth());
        assertEquals(15L, result.getPendingTransportVerifications());
        assertNotNull(result.getTopTransports());
        assertEquals(2, result.getTopTransports().size());
        verify(userRepository).count();
        verify(transportRepository).findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus.APPROVED);
    }

    @Test
    void getPlatformStatistics_Success() {
        when(userRepository.count()).thenReturn(500L);
        when(userRepository.countByRole(UserRole.CUSTOMER)).thenReturn(400L);
        when(userRepository.countByRole(UserRole.TRANSPORT)).thenReturn(80L);
        when(userRepository.countByRole(UserRole.MANAGER)).thenReturn(20L);
        when(userRepository.countByIsActive(true)).thenReturn(450L);
        when(userRepository.countByIsVerified(true)).thenReturn(420L);
        when(userRepository.countByCreatedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(5L)
                .thenReturn(25L)
                .thenReturn(50L)
                .thenReturn(40L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.PENDING)).thenReturn(10L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.APPROVED)).thenReturn(75L);
        when(transportRepository.findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus.APPROVED))
                .thenReturn(topTransports);

        Map<String, Object> result = dashboardService.getPlatformStatistics();

        assertNotNull(result);
        assertEquals(500L, result.get("totalUsers"));
        assertEquals(400L, result.get("totalCustomers"));
        assertEquals(80L, result.get("totalTransports"));
        assertEquals(20L, result.get("totalManagers"));
        assertEquals(450L, result.get("activeUsers"));
        assertEquals(50L, result.get("inactiveUsers"));
        assertEquals(420L, result.get("verifiedUsers"));
        assertEquals(75L, result.get("verifiedTransports"));
        assertEquals(5L, result.get("newUsersToday"));
        assertEquals(25L, result.get("newUsersThisWeek"));
        assertEquals(50L, result.get("newUsersThisMonth"));
        assertEquals(10L, result.get("pendingTransportVerifications"));
        assertNotNull(result.get("topTransports"));
    }

    @Test
    void getAdminDashboardStats_WithZeroGrowthRate() {
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.countByRole(UserRole.CUSTOMER)).thenReturn(80L);
        when(userRepository.countByRole(UserRole.TRANSPORT)).thenReturn(15L);
        when(userRepository.countByRole(UserRole.MANAGER)).thenReturn(5L);
        when(userRepository.countByIsActive(true)).thenReturn(90L);
        when(userRepository.countByIsVerified(true)).thenReturn(85L);
        when(userRepository.countByCreatedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0L)
                .thenReturn(0L)
                .thenReturn(0L)
                .thenReturn(0L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.PENDING)).thenReturn(0L);
        when(transportRepository.countByVerificationStatus(VerificationStatus.APPROVED)).thenReturn(0L);
        when(transportRepository.findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus.APPROVED))
                .thenReturn(new ArrayList<>());

        AdminDashboardStatsResponse result = dashboardService.getAdminDashboardStats();

        assertNotNull(result);
        assertEquals(100L, result.getTotalUsers());
        assertEquals(0L, result.getNewUsersToday());
        assertEquals(0L, result.getNewUsersThisWeek());
        assertEquals(0L, result.getNewUsersThisMonth());
        assertEquals("0.0%", result.getUserGrowthRate());
        assertTrue(result.getTopTransports().isEmpty());
    }
}
