package com.homeexpress.home_express_api.controller.transport;

import com.homeexpress.home_express_api.dto.request.SubmitQuotationRequest;
import com.homeexpress.home_express_api.dto.response.QuotationResponse;
import com.homeexpress.home_express_api.dto.response.TransportDashboardStatsResponse;
import com.homeexpress.home_express_api.dto.response.TransportQuotationSummaryResponse;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.QuotationService;
import com.homeexpress.home_express_api.service.TransportDashboardService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/transport")
@Validated
public class TransportDashboardController {

    private static final int MAX_QUOTATION_LIMIT = 50;

    private final TransportDashboardService dashboardService;

    private final QuotationService quotationService;

    private final UserRepository userRepository;

    @GetMapping("/dashboard/stats")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> getDashboardStats(Authentication authentication) {
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (!isTransport(user)) {
            return forbidden();
        }

        TransportDashboardStatsResponse stats = dashboardService.getDashboardStats(user.getUserId());
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/quotations")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> submitQuotation(
            @Valid @RequestBody SubmitQuotationRequest request,
            Authentication authentication) {
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (!isTransport(user)) {
            return forbidden();
        }

        QuotationResponse response = quotationService.submitQuotation(request, user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "quotationId", response.getQuotationId(),
                "message", "Quotation submitted successfully",
                "totalPrice", response.getQuotedPrice(),
                "expiresAt", response.getExpiresAt()
        ));
    }

    @GetMapping("/quotations")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> getRecentQuotations(Authentication authentication,
                                                  @RequestParam(defaultValue = "10")
                                                  @Min(1) @Max(MAX_QUOTATION_LIMIT) int limit) {
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (!isTransport(user)) {
            return forbidden();
        }

        List<TransportQuotationSummaryResponse> quotations = dashboardService
                .getRecentQuotations(user.getUserId(), limit);
        return ResponseEntity.ok(quotations);
    }

    private boolean isTransport(User user) {
        return user != null && user.getRole() == UserRole.TRANSPORT;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only transport accounts can access this resource"));
    }
}


