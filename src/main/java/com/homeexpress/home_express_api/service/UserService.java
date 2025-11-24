package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.request.ChangePasswordRequest;
import com.homeexpress.home_express_api.dto.request.UpdateProfileRequest;
import com.homeexpress.home_express_api.dto.request.UpdateUserRequest;
import com.homeexpress.home_express_api.dto.response.*;
import com.homeexpress.home_express_api.entity.Customer;
import com.homeexpress.home_express_api.entity.Manager;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.exception.BadRequestException;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.CustomerRepository;
import com.homeexpress.home_express_api.repository.ManagerRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;

    private final CustomerRepository customerRepository;

    private final TransportRepository transportRepository;

    private final ManagerRepository managerRepository;

    private final PasswordEncoder passwordEncoder;

    private final JdbcTemplate jdbcTemplate;

    // Lấy danh sách users, có thể filter theo role
    public UserListResponse getAllUsers(UserRole role, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<User> userPage = role != null
                ? userRepository.findByRole(role, pageable)
                : userRepository.findAll(pageable);

        List<UserResponse> userResponses = userPage.getContent().stream()
                .map(this::convertToUserResponse)
                .collect(Collectors.toList());

        return new UserListResponse(
                userResponses,
                userPage.getTotalElements(),
                userPage.getTotalPages(),
                userPage.getNumber()
        );
    }

    public UserResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return convertToUserResponse(user);
    }

    @Transactional
    public UserResponse updateUser(Long userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (StringUtils.hasText(request.getEmail())
                && !user.getEmail().equalsIgnoreCase(request.getEmail())
                && userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email đã được sử dụng");
        }

        if (StringUtils.hasText(request.getEmail())) {
            user.setEmail(request.getEmail());
        }

        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }

        User updatedUser = userRepository.save(user);
        return convertToUserResponse(updatedUser);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setIsActive(false);
        userRepository.save(user);
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (StringUtils.hasText(request.getEmail())
                && !user.getEmail().equalsIgnoreCase(request.getEmail())
                && userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email đã được sử dụng");
        }

        if (StringUtils.hasText(request.getEmail())) {
            user.setEmail(request.getEmail());
        }

        if (request.getAvatar() != null) {
            user.setAvatarUrl(request.getAvatar());
        }

        switch (user.getRole()) {
            case CUSTOMER -> applyCustomerProfile(userId, request);
            case TRANSPORT -> applyTransportProfile(userId, request);
            case MANAGER -> applyManagerProfile(userId, request);
            default -> throw new IllegalStateException("Unsupported role: " + user.getRole());
        }

        userRepository.save(user);
        return getProfile(userId);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return buildProfileResponse(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Mật khẩu cũ không đúng");
        }

        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new RuntimeException("Mật khẩu mới phải khác mật khẩu cũ");
        }

        String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
        user.setPasswordHash(newPasswordHash);
        user.setLastPasswordChange(LocalDateTime.now());
        userRepository.save(user);

        logAuditEvent(userId, "users", userId, "PASSWORD_CHANGE", null);
    }

    private void applyCustomerProfile(Long userId, UpdateProfileRequest request) {
        Customer customer = customerRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", userId));

        if (StringUtils.hasText(request.getFullName())) {
            customer.setFullName(request.getFullName());
        }
        if (StringUtils.hasText(request.getPhone())) {
            customer.setPhone(request.getPhone());
        }
        if (request.getAddress() != null) {
            customer.setAddress(request.getAddress());
        }
        // avatar_url moved to User entity
        if (request.getPreferredLanguage() != null) {
            customer.setPreferredLanguage(request.getPreferredLanguage());
        }
        if (request.getDateOfBirth() != null) {
            parseLocalDate(request.getDateOfBirth()).ifPresent(customer::setDateOfBirth);
        }

        customerRepository.save(customer);
    }

    private void applyTransportProfile(Long userId, UpdateProfileRequest request) {
        Transport transport = transportRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transport", "id", userId));

        if (StringUtils.hasText(request.getCompanyName())) {
            transport.setCompanyName(request.getCompanyName());
        }
        if (StringUtils.hasText(request.getPhone())) {
            transport.setPhone(request.getPhone());
        }
        if (request.getAddress() != null) {
            transport.setAddress(request.getAddress());
        }
        if (request.getCity() != null) {
            transport.setCity(request.getCity());
        }
        if (request.getDistrict() != null) {
            transport.setDistrict(request.getDistrict());
        }
        if (request.getWard() != null) {
            transport.setWard(request.getWard());
        }
        if (StringUtils.hasText(request.getTaxCode())) {
            transport.setTaxCode(request.getTaxCode());
        }
        if (request.getBankName() != null) {
            transport.setBankName(request.getBankName());
        }
        if (request.getBankCode() != null) {
            String normalizedBankCode = normalizeBankCode(request.getBankCode());
            if (StringUtils.hasText(normalizedBankCode)) {
                if (!bankCodeExists(normalizedBankCode)) {
                    throw new BadRequestException("Bank code is not supported. Please choose a valid Vietnamese bank.");
                }
                transport.setBankCode(normalizedBankCode);
                if (request.getBankName() == null) {
                    String bankName = lookupBankName(normalizedBankCode);
                    if (bankName != null) {
                        transport.setBankName(bankName);
                    }
                }
            } else {
                transport.setBankCode(null);
            }
        }
        if (request.getBankAccountNumber() != null) {
            transport.setBankAccountNumber(request.getBankAccountNumber());
        }
        if (request.getBankAccountHolder() != null) {
            transport.setBankAccountHolder(request.getBankAccountHolder());
        }

        transportRepository.save(transport);
    }

    private void applyManagerProfile(Long userId, UpdateProfileRequest request) {
        Manager manager = managerRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager", "id", userId));

        if (StringUtils.hasText(request.getFullName())) {
            manager.setFullName(request.getFullName());
        }
        if (StringUtils.hasText(request.getPhone())) {
            manager.setPhone(request.getPhone());
        }

        managerRepository.save(manager);
    }

    private boolean bankCodeExists(String bankCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vn_banks WHERE bank_code = ? AND is_active = TRUE",
                Integer.class,
                bankCode);
        return count != null && count > 0;
    }

    private String lookupBankName(String bankCode) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT bank_name FROM vn_banks WHERE bank_code = ? AND is_active = TRUE",
                    String.class,
                    bankCode);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeBankCode(String bankCode) {
        return bankCode != null ? bankCode.trim().toUpperCase() : null;
    }

    private ProfileResponse buildProfileResponse(User user) {
        ProfileResponse response = new ProfileResponse();
        response.setUser(mapUserSummary(user));

        switch (user.getRole()) {
            case CUSTOMER -> response.setCustomer(mapCustomerProfile(user.getUserId()));
            case TRANSPORT -> response.setTransport(mapTransportProfile(user.getUserId()));
            case MANAGER -> response.setManager(mapManagerProfile(user.getUserId()));
        }
        return response;
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getUserSummary(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return mapUserSummary(user);
    }

    private UserSummaryResponse mapUserSummary(User user) {
        UserSummaryResponse summary = new UserSummaryResponse();
        summary.setUserId(user.getUserId());
        summary.setEmail(user.getEmail());
        summary.setRole(user.getRole().name());
        summary.setIsActive(user.getIsActive());
        summary.setIsVerified(user.getIsVerified());
        summary.setAvatar(user.getAvatarUrl());
        summary.setCreatedAt(user.getCreatedAt());
        return summary;
    }

    private CustomerProfileResponse mapCustomerProfile(Long userId) {
        return customerRepository.findById(userId)
                .map(customer -> {
                    CustomerProfileResponse profile = new CustomerProfileResponse();
                    profile.setCustomerId(customer.getCustomerId());
                    profile.setFullName(customer.getFullName());
                    profile.setPhone(customer.getPhone());
                    profile.setAddress(customer.getAddress());
                    profile.setDateOfBirth(customer.getDateOfBirth());
                    profile.setAvatarUrl(customer.getUser().getAvatarUrl());
                    profile.setPreferredLanguage(customer.getPreferredLanguage());
                    profile.setCreatedAt(customer.getCreatedAt());
                    profile.setUpdatedAt(customer.getUpdatedAt());
                    return profile;
                })
                .orElse(null);
    }

    private TransportProfileResponse mapTransportProfile(Long userId) {
        return transportRepository.findById(userId)
                .map(transport -> {
                    TransportProfileResponse profile = new TransportProfileResponse();
                    profile.setTransportId(transport.getTransportId());
                    profile.setUserId(transport.getTransportId());
                    profile.setCompanyName(transport.getCompanyName());
                    profile.setBusinessLicenseNumber(transport.getBusinessLicenseNumber());
                    profile.setTaxCode(transport.getTaxCode());
                    profile.setPhone(transport.getPhone());
                    profile.setAddress(transport.getAddress());
                    profile.setCity(transport.getCity());
                    profile.setDistrict(transport.getDistrict());
                    profile.setWard(transport.getWard());
                    profile.setVerificationStatus(transport.getVerificationStatus().name());
                    profile.setVerifiedAt(transport.getVerifiedAt());
                    profile.setVerifiedBy(transport.getVerifiedBy() != null ? transport.getVerifiedBy().getUserId() : null);
                    profile.setTotalBookings(transport.getTotalBookings());
                    profile.setCompletedBookings(transport.getCompletedBookings());
                    profile.setCancelledBookings(transport.getCancelledBookings());
                    profile.setAverageRating(transport.getAverageRating());
                    profile.setNationalIdNumber(transport.getNationalIdNumber());
                    profile.setNationalIdType(transport.getNationalIdType() != null ? transport.getNationalIdType().name() : null);
                    profile.setNationalIdIssueDate(transport.getNationalIdIssueDate());
                    profile.setBankName(transport.getBankName());
                    profile.setBankCode(transport.getBankCode());
                    profile.setBankAccountNumber(transport.getBankAccountNumber());
                    profile.setBankAccountHolder(transport.getBankAccountHolder());
                    profile.setCreatedAt(transport.getCreatedAt());
                    profile.setUpdatedAt(transport.getUpdatedAt());
                    return profile;
                })
                .orElse(null);
    }

    private ManagerProfileResponse mapManagerProfile(Long userId) {
        return managerRepository.findById(userId)
                .map(manager -> {
                    ManagerProfileResponse profile = new ManagerProfileResponse();
                    profile.setManagerId(manager.getManagerId());
                    profile.setFullName(manager.getFullName());
                    profile.setPhone(manager.getPhone());
                    profile.setEmployeeId(manager.getEmployeeId());
                    profile.setDepartment(manager.getDepartment());
                    profile.setPermissions(manager.getPermissions());
                    profile.setCreatedAt(manager.getCreatedAt());
                    profile.setUpdatedAt(manager.getUpdatedAt());
                    return profile;
                })
                .orElse(null);
    }

    private Optional<LocalDate> parseLocalDate(String value) {
        try {
            return StringUtils.hasText(value) ? Optional.of(LocalDate.parse(value)) : Optional.empty();
        } catch (DateTimeParseException ex) {
            throw new RuntimeException("Ngày sinh không hợp lệ (định dạng YYYY-MM-DD)");
        }
    }

    private void logAuditEvent(Long actorId, String tableName, Long rowPk, String action, String details) {
        if (jdbcTemplate != null) {
            try {
                String sql = "INSERT INTO audit_log (table_name, action, row_pk, actor_id, new_data) VALUES (?, ?, ?, ?, ?)";
                jdbcTemplate.update(sql, tableName, action, rowPk, actorId, details);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private UserResponse convertToUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(null);
        response.setEmail(user.getEmail());
        response.setPhone(null);
        response.setRole(user.getRole().name());
        response.setAvatar(user.getAvatarUrl());
        response.setIsActive(user.getIsActive());
        response.setIsVerified(user.getIsVerified());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }
}
