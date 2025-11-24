package com.homeexpress.home_express_api.controller.transport;

import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.dto.transport.TransportEarningsStatsResponse;
import com.homeexpress.home_express_api.dto.transport.TransportTransactionDto;
import com.homeexpress.home_express_api.dto.transport.TransportWalletReportResponse;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.TransportFinanceService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport")
public class TransportFinanceController {

    private final TransportFinanceService financeService;
    private final UserRepository userRepository;

    public TransportFinanceController(TransportFinanceService financeService, UserRepository userRepository) {
        this.financeService = financeService;
        this.userRepository = userRepository;
    }

    @GetMapping("/earnings/stats")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> getEarningsStats(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication required"));
        }
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only transport accounts can access this resource"));
        }

        TransportEarningsStatsResponse stats = financeService.getEarningsStats(user.getUserId());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> getTransactions(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication required"));
        }
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only transport accounts can access this resource"));
        }

        List<TransportTransactionDto> transactions = financeService.getTransactions(user.getUserId());
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/earnings/wallet-report")
    @PreAuthorize("hasRole('TRANSPORT')")
    public ResponseEntity<?> getWalletReport(
            @RequestParam(value = "days", required = false) Integer days,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication required"));
        }
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only transport accounts can access this resource"));
        }

        TransportWalletReportResponse report = financeService.getWalletReport(user.getUserId(), days);
        return ResponseEntity.ok(report);
    }
}
