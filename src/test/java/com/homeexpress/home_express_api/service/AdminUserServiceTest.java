package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.response.UserListResponse;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransportRepository transportRepository;

    @Mock
    private ManagerRepository managerRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private UserSessionService userSessionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdminUserService adminUserService;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDeactivateUser_Success() {
        // Given
        Long userId = 1L;
        String reason = "Violation of terms";
        Long adminId = 99L;

        User user = new User();
        user.setUserId(userId);
        user.setIsActive(true);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(adminId); // For AuthenticationUtils.getUserId
        when(authentication.isAuthenticated()).thenReturn(true);

        // When
        adminUserService.deactivateUser(userId, reason);

        // Then
        assertFalse(user.getIsActive());
        verify(userRepository).save(user);
        verify(userSessionService).revokeAllUserSessions(userId, reason);
        
        // Verify audit log
        verify(jdbcTemplate).update(
                eq("INSERT INTO audit_log (table_name, action, row_pk, actor_id, new_data) VALUES (?, ?, ?, ?, ?)"),
                eq("users"),
                eq("USER_DEACTIVATED"),
                eq(userId),
                eq(adminId),
                contains(reason)
        );
    }

    @Test
    void testGetAllUsersWithProfiles_Customer() {
        // Given
        User customerUser = new User();
        customerUser.setUserId(1L);
        customerUser.setRole(UserRole.CUSTOMER);
        customerUser.setEmail("customer@test.com");

        Customer customer = new Customer();
        customer.setCustomerId(10L);
        customer.setFullName("Test Customer");
        
        // Mock the relationship (assuming we can set it or mock the User object)
        // Since User is a simple entity, we might need to set the field via reflection or add a setter if Lombok @Data is used.
        // Since we modified User to have these fields, we assume setters are generated or we can use reflection.
        // However, Lombok @Data generates setters.
        customerUser.setCustomer(customer);

        Page<User> userPage = new PageImpl<>(Collections.singletonList(customerUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(userPage);

        // When
        UserListResponse response = adminUserService.getAllUsersWithProfiles(null, null, null, 0, 10);

        // Then
        List<Map<String, Object>> usersList = (List<Map<String, Object>>) response.getUsers();
        assertEquals(1, usersList.size());
        Map<String, Object> userMap = usersList.get(0);
        Map<String, Object> profileMap = (Map<String, Object>) userMap.get("profile");
        
        assertEquals("Test Customer", profileMap.get("full_name"));
        assertEquals(10L, profileMap.get("customer_id"));
    }
}
