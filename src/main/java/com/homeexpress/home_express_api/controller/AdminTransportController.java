package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.TransportVerificationRequest;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.response.TransportVerificationListResponse;
import com.homeexpress.home_express_api.dto.response.VehicleListResponse;
import com.homeexpress.home_express_api.dto.response.VehicleResponse;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.Vehicle;
import com.homeexpress.home_express_api.entity.VehicleStatus;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.repository.VehicleRepository;
import com.homeexpress.home_express_api.service.TransportService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/transports")
public class AdminTransportController {

    private final TransportService transportService;

    private final VehicleRepository vehicleRepository;

    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<List<Transport>> getAllTransports(
            @RequestParam(required = false) VerificationStatus status) {
        
        List<Transport> transports;
        if (status != null) {
            transports = transportService.getTransportsByStatus(status);
        } else {
            transports = transportService.getAllTransports();
        }
        
        return ResponseEntity.ok(transports);
    }

    @GetMapping("/verification")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<TransportVerificationListResponse> getTransportsForVerification(
            @RequestParam(required = false) VerificationStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String search) {
        
        Page<Transport> transportsPage = transportService.searchTransports(status, search, page, limit);

        // Map to DTO
        List<TransportVerificationListResponse.TransportWithUser> data = transportsPage.getContent().stream()
                .map(transport -> {
                    TransportVerificationListResponse.TransportInfo transportInfo =
                            TransportVerificationListResponse.TransportInfo.builder()
                                    .transportId(transport.getTransportId())
                                    .companyName(transport.getCompanyName())
                                    .businessLicenseNumber(transport.getBusinessLicenseNumber())
                                    .taxCode(transport.getTaxCode())
                                    .phone(transport.getPhone())
                                    .address(transport.getAddress())
                                    .district(transport.getDistrict())
                                    .city(transport.getCity())
                                    .nationalIdType(transport.getNationalIdType() != null ? transport.getNationalIdType().name() : null)
                                    .nationalIdNumber(transport.getNationalIdNumber())
                                    .bankCode(transport.getBankCode())
                                    .bankName(transport.getBankName()) // Added
                                    .bankAccountNumber(transport.getBankAccountNumber())
                                    .bankAccountHolder(transport.getBankAccountHolder())
                                    .licensePhotoUrl(transport.getLicensePhotoUrl()) // Added
                                    .insurancePhotoUrl(transport.getInsurancePhotoUrl()) // Added
                                    .nationalIdPhotoFrontUrl(transport.getNationalIdPhotoFrontUrl()) // Added
                                    .nationalIdPhotoBackUrl(transport.getNationalIdPhotoBackUrl()) // Added
                                    .verificationStatus(transport.getVerificationStatus())
                                    .verifiedAt(transport.getVerifiedAt())
                                    .verificationNotes(transport.getVerificationNotes())
                                    .totalBookings(transport.getTotalBookings())
                                    .completedBookings(transport.getCompletedBookings())
                                    .createdAt(transport.getCreatedAt())
                                    .build();

                    User user = transport.getUser();
                    TransportVerificationListResponse.UserInfo userInfo =
                            TransportVerificationListResponse.UserInfo.builder()
                                    .userId(user.getUserId())
                                    .email(user.getEmail())
                                    .isActive(user.getIsActive())
                                    .isVerified(user.getIsVerified())
                                    .createdAt(user.getCreatedAt())
                                    .build();

                    return TransportVerificationListResponse.TransportWithUser.builder()
                            .transport(transportInfo)
                            .user(userInfo)
                            .build();
                })
                .collect(Collectors.toList());

        TransportVerificationListResponse response = TransportVerificationListResponse.builder()
                .data(data)
                .total((int) transportsPage.getTotalElements())
                .page(page)
                .limit(limit)
                .totalPages(transportsPage.getTotalPages())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Transport> getTransportById(@PathVariable Long id) {
        Transport transport = transportService.getTransportById(id);
        return ResponseEntity.ok(transport);
    }

    @GetMapping("/{id}/vehicles")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<VehicleListResponse>> getTransportVehicles(
            @PathVariable Long id,
            @RequestParam(required = false) VehicleStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        // Ensure transport exists
        transportService.getTransportById(id);

        List<Vehicle> vehicles = status != null
                ? vehicleRepository.findByTransportTransportIdAndStatus(id, status)
                : vehicleRepository.findByTransportTransportId(id);

        int totalItems = vehicles.size();
        int totalPages = (int) Math.ceil((double) totalItems / size);
        int startIndex = Math.max(0, (page - 1) * size);
        int endIndex = Math.min(startIndex + size, totalItems);

        List<VehicleResponse> paginatedVehicles = vehicles.stream()
                .skip(startIndex)
                .limit(Math.max(0, endIndex - startIndex))
                .map(VehicleResponse::fromEntity)
                .toList();

        VehicleListResponse listResponse = VehicleListResponse.builder()
                .vehicles(paginatedVehicles)
                .pagination(VehicleListResponse.Pagination.builder()
                        .currentPage(page)
                        .totalPages(totalPages)
                        .totalItems(totalItems)
                        .itemsPerPage(size)
                        .build())
                .build();

        return ResponseEntity.ok(ApiResponse.success(listResponse));
    }

    @PatchMapping("/{id}/verify")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> verifyTransport(
            @PathVariable Long id,
            @Valid @RequestBody TransportVerificationRequest request,
            Authentication authentication) {
        
        User currentUser = resolveCurrentUser(authentication);
        
        Transport transport;
        if (request.getIsVerified()) {
            transport = transportService.verifyTransport(id, currentUser.getUserId(), request.getVerificationNotes());
        } else {
            transport = transportService.rejectTransport(id, currentUser.getUserId(), request.getVerificationNotes());
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", request.getIsVerified() ? "Transport verified successfully" : "Transport rejected");
        response.put("transport", transport);
        
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> rejectTransport(
            @PathVariable Long id,
            @Valid @RequestBody TransportVerificationRequest request,
            Authentication authentication) {
        
        User currentUser = resolveCurrentUser(authentication);
        Transport transport = transportService.rejectTransport(id, currentUser.getUserId(), request.getVerificationNotes());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Transport rejected");
        response.put("transport", transport);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, String>> getTransportDocuments(@PathVariable Long id) {
        Transport transport = transportService.getTransportById(id);
        
        Map<String, String> documents = new HashMap<>();
        documents.put("licensePhotoUrl", transport.getLicensePhotoUrl());
        documents.put("insurancePhotoUrl", transport.getInsurancePhotoUrl());
        documents.put("nationalIdPhotoFrontUrl", transport.getNationalIdPhotoFrontUrl());
        documents.put("nationalIdPhotoBackUrl", transport.getNationalIdPhotoBackUrl());
        
        return ResponseEntity.ok(documents);
    }

    private User resolveCurrentUser(Authentication authentication) {
        Long userId = AuthenticationUtils.getUserId(authentication);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }
}
