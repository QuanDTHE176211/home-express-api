package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.VehiclePricingRequest;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.response.VehiclePricingListResponse;
import com.homeexpress.home_express_api.dto.response.VehiclePricingResponse;
import com.homeexpress.home_express_api.service.VehiclePricingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transport/pricing/vehicles")
public class VehiclePricingController {

    @Autowired
    private VehiclePricingService vehiclePricingService;

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<?>> createVehiclePricing(@Valid @RequestBody VehiclePricingRequest request) {
        try {
            VehiclePricingResponse response = vehiclePricingService.createVehiclePricing(request);
            Map<String, Object> data = Map.of("pricingId", response.getVehiclePricingId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(data, "Vehicle pricing created successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<VehiclePricingListResponse>> getAllVehiclePricing(
            @RequestParam(required = false) Long transportId,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(required = false) Boolean active) {
        
        List<VehiclePricingResponse> pricingList;
        
        if (transportId != null) {
            pricingList = vehiclePricingService.getVehiclePricingByTransport(transportId);
        } else if (vehicleType != null) {
            pricingList = vehiclePricingService.getVehiclePricingByVehicleType(vehicleType);
        } else if (active != null && active) {
            pricingList = vehiclePricingService.getActiveVehiclePricing();
        } else {
            pricingList = vehiclePricingService.getAllVehiclePricing();
        }
        
        VehiclePricingListResponse response = VehiclePricingListResponse.builder()
                .pricingRules(pricingList)
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/current/{transportId}/{vehicleType}")
    public ResponseEntity<ApiResponse<VehiclePricingResponse>> getCurrentActivePricing(
            @PathVariable Long transportId,
            @PathVariable String vehicleType) {
        try {
            VehiclePricingResponse response = vehiclePricingService.getCurrentActivePricing(transportId, vehicleType);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VehiclePricingResponse>> getVehiclePricingById(@PathVariable Long id) {
        try {
            VehiclePricingResponse response = vehiclePricingService.getVehiclePricingById(id);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<VehiclePricingResponse>> updateVehiclePricing(
            @PathVariable Long id,
            @Valid @RequestBody VehiclePricingRequest request) {
        try {
            VehiclePricingResponse response = vehiclePricingService.updateVehiclePricing(id, request);
            return ResponseEntity.ok(ApiResponse.success(response, "Vehicle pricing updated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deactivateVehiclePricing(@PathVariable Long id) {
        try {
            vehiclePricingService.deactivateVehiclePricing(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Vehicle pricing deactivated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
