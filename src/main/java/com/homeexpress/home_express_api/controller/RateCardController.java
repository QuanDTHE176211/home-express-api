package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.RateCardRequest;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.response.RateCardResponse;
import com.homeexpress.home_express_api.service.RateCardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport/pricing/rate-cards")
public class RateCardController {

    private final RateCardService rateCardService;

    public RateCardController(RateCardService rateCardService) {
        this.rateCardService = rateCardService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RateCardResponse>>> getRateCards(@RequestParam Long transportId) {
        List<RateCardResponse> cards = rateCardService.getRateCardsForTransport(transportId);
        return ResponseEntity.ok(ApiResponse.success(cards));
    }

    @PostMapping
    @PreAuthorize("hasRole('TRANSPORT') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<RateCardResponse>> createRateCard(
            @RequestParam Long transportId,
            @Valid @RequestBody RateCardRequest request) {
        RateCardResponse response = rateCardService.createOrUpdateRateCard(transportId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "Rate card created successfully"));
    }
    
    @DeleteMapping("/{id}")
     @PreAuthorize("hasRole('TRANSPORT') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteRateCard(
            @RequestParam Long transportId,
            @PathVariable Long id) {
        rateCardService.deleteRateCard(transportId, id);
        return ResponseEntity.ok(ApiResponse.success(null, "Rate card deleted"));
    }
}
