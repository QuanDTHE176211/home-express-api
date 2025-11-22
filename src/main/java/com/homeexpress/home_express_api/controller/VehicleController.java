package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.EligibleVehiclesRequest;
import com.homeexpress.home_express_api.dto.request.VehicleRequest;
import com.homeexpress.home_express_api.dto.request.VehicleStatusUpdateRequest;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.response.VehicleListResponse;
import com.homeexpress.home_express_api.dto.response.VehicleResponse;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.VehicleService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transport/vehicles")
public class VehicleController {

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> createVehicle(
            @Valid @RequestBody VehicleRequest request,
            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            VehicleResponse response = vehicleService.createVehicle(request, user.getUserId());
            Map<String, Object> data = Map.of(
                    "vehicleId", response.getVehicleId(),
                    "vehicle", response
            );
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(data, "Vehicle created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<VehicleListResponse>> getVehicles(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            User user = resolveUser(authentication);
            List<VehicleResponse> vehicles = vehicleService.getVehiclesByTransport(user.getUserId());
            
            // Filter by status if provided
            if (status != null && !"all".equals(status)) {
                vehicles = vehicles.stream()
                        .filter(v -> status.equalsIgnoreCase(v.getStatus().name()))
                        .toList();
            }
            
            // Calculate pagination
            int totalItems = vehicles.size();
            int totalPages = (int) Math.ceil((double) totalItems / size);
            int startIndex = (page - 1) * size;
            int endIndex = Math.min(startIndex + size, totalItems);
            
            List<VehicleResponse> paginatedVehicles = vehicles.subList(
                    Math.max(0, startIndex),
                    Math.max(0, endIndex)
            );
            
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
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VehicleResponse>> getVehicleById(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            VehicleResponse response = vehicleService.getVehicleById(id, user.getUserId());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<VehicleResponse>> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody VehicleRequest request,
            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            VehicleResponse response = vehicleService.updateVehicle(id, request, user.getUserId());
            return ResponseEntity.ok(ApiResponse.success(response, "Vehicle updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteVehicle(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            vehicleService.deleteVehicle(id, user.getUserId());
            return ResponseEntity.ok(ApiResponse.success(null, "Vehicle deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<VehicleResponse>> updateVehicleStatus(
            @PathVariable Long id,
            @Valid @RequestBody VehicleStatusUpdateRequest request,
            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            VehicleResponse response = vehicleService.updateVehicleStatus(id, request, user.getUserId());
            return ResponseEntity.ok(ApiResponse.success(response, "Vehicle status updated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/eligible")
    public ResponseEntity<ApiResponse<List<VehicleResponse>>> getEligibleVehicles(
            @Valid @RequestBody EligibleVehiclesRequest request) {
        try {
            List<VehicleResponse> eligibleVehicles = vehicleService.getEligibleVehicles(
                    request.getTotalWeight(),
                    request.getTotalVolume(),
                    request.getRequiresTailLift() != null && request.getRequiresTailLift(),
                    request.getRequiresTools() != null && request.getRequiresTools()
            );
            return ResponseEntity.ok(ApiResponse.success(eligibleVehicles));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    private User resolveUser(Authentication authentication) {
        return AuthenticationUtils.getUser(authentication, userRepository);
    }
}
