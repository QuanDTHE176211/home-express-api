package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.CategoryPricingRequest;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.response.CategoryPricingListResponse;
import com.homeexpress.home_express_api.dto.response.CategoryPricingResponse;
import com.homeexpress.home_express_api.service.CategoryPricingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transport/pricing/categories")
public class CategoryPricingController {

    @Autowired
    private CategoryPricingService categoryPricingService;

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<?>> createCategoryPricing(@Valid @RequestBody CategoryPricingRequest request) {
        try {
            CategoryPricingResponse response = categoryPricingService.createCategoryPricing(request);
            Map<String, Object> data = Map.of("pricingId", response.getCategoryPricingId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(data, "Category pricing created successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CategoryPricingListResponse>> getAllCategoryPricing(
            @RequestParam(required = false) Long transportId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean active) {
        
        List<CategoryPricingResponse> pricingList;
        
        if (transportId != null) {
            pricingList = categoryPricingService.getCategoryPricingByTransport(transportId);
        } else if (categoryId != null) {
            pricingList = categoryPricingService.getCategoryPricingByCategory(categoryId);
        } else if (active != null && active) {
            pricingList = categoryPricingService.getActiveCategoryPricing();
        } else {
            pricingList = categoryPricingService.getAllCategoryPricing();
        }
        
        CategoryPricingListResponse response = CategoryPricingListResponse.builder()
                .pricingRules(pricingList)
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/current/{categoryId}")
    public ResponseEntity<ApiResponse<CategoryPricingResponse>> getCurrentActivePricing(
            @PathVariable Long categoryId,
            @RequestParam Long transportId,
            @RequestParam(required = false) Long sizeId) {
        try {
            CategoryPricingResponse response = categoryPricingService.getCurrentActivePricing(
                    transportId, categoryId, sizeId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryPricingResponse>> getCategoryPricingById(@PathVariable Long id) {
        try {
            CategoryPricingResponse response = categoryPricingService.getCategoryPricingById(id);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<CategoryPricingResponse>> updateCategoryPricing(
            @PathVariable Long id,
            @Valid @RequestBody CategoryPricingRequest request) {
        try {
            CategoryPricingResponse response = categoryPricingService.updateCategoryPricing(id, request);
            return ResponseEntity.ok(ApiResponse.success(response, "Category pricing updated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deactivateCategoryPricing(@PathVariable Long id) {
        try {
            categoryPricingService.deactivateCategoryPricing(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Category pricing deactivated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
