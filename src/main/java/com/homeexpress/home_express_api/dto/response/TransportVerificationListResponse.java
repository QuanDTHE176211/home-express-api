package com.homeexpress.home_express_api.dto.response;

import com.homeexpress.home_express_api.entity.VerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportVerificationListResponse {
    private List<TransportWithUser> data;
    private int total;
    private int page;
    private int limit;
    private int totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransportWithUser {
        private TransportInfo transport;
        private UserInfo user;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransportInfo {
        private Long transportId;
        private String companyName;
        private String businessLicenseNumber;
        private String taxCode;
        private String phone;
        private String address;
        private String district;
        private String city;
        private String nationalIdType;
        private String nationalIdNumber;
        private String bankCode;
        private String bankAccountNumber;
        private String bankAccountHolder;
        private String bankName; // Added field
        private String licensePhotoUrl; // Added field
        private String insurancePhotoUrl; // Added field
        private String nationalIdPhotoFrontUrl; // Added field
        private String nationalIdPhotoBackUrl; // Added field
        private VerificationStatus verificationStatus;
        private LocalDateTime verifiedAt;
        private String verificationNotes;
        private Integer totalBookings;
        private Integer completedBookings;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long userId;
        private String email;
        private Boolean isActive;
        private Boolean isVerified;
        private LocalDateTime createdAt;
    }
}
