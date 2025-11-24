package com.homeexpress.home_express_api.controller.transport;

import com.homeexpress.home_express_api.dto.payout.PayoutDTO;
import com.homeexpress.home_express_api.dto.transport.TransportPayoutListDTO;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.BookingSettlementRepository;
import com.homeexpress.home_express_api.repository.TransportPayoutRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.PayoutService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/transport/payouts")
public class TransportPayoutController {

    private final TransportPayoutRepository payoutRepository;

    private final BookingSettlementRepository settlementRepository;

    private final UserRepository userRepository;

    private final PayoutService payoutService;

    @PostMapping("/request")
    public ResponseEntity<?> requestPayout(Authentication authentication) {
        User user = getUserFromAuth(authentication);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only transport companies can access this endpoint"));
        }

        try {
            PayoutDTO payout = payoutService.createPayoutBatch(user.getUserId());
            return ResponseEntity.ok(payout);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create payout request: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getMyPayouts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        User user = getUserFromAuth(authentication);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only transport companies can access this endpoint"));
        }

        Long transportId = user.getUserId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        List<TransportPayout> payouts = payoutRepository.findByTransportIdOrderByCreatedAtDesc(transportId);

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), payouts.size());
        List<TransportPayout> paginatedList = payouts.subList(start, end);

        List<TransportPayoutListDTO> payoutDTOs = paginatedList.stream()
                .map(this::mapToListDTO)
                .collect(Collectors.toList());

        Page<TransportPayoutListDTO> payoutPage = new PageImpl<>(
                payoutDTOs,
                pageable,
                payouts.size()
        );

        return ResponseEntity.ok(Map.of(
                "payouts", payoutPage.getContent(),
                "currentPage", payoutPage.getNumber(),
                "totalItems", payoutPage.getTotalElements(),
                "totalPages", payoutPage.getTotalPages()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPayoutDetails(
            @PathVariable Long id,
            Authentication authentication) {

        User user = getUserFromAuth(authentication);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only transport companies can access this endpoint"));
        }

        TransportPayout payout = payoutRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payout not found"));

        if (!payout.getTransportId().equals(user.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only view your own payouts"));
        }

        PayoutDTO dto = payoutService.getPayoutDetails(id);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingSettlements(Authentication authentication) {

        User user = getUserFromAuth(authentication);
        if (user.getRole() != UserRole.TRANSPORT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only transport companies can access this endpoint"));
        }

        Long transportId = user.getUserId();

        List<BookingSettlement> pendingSettlements = settlementRepository
                .findByTransportIdAndStatus(transportId, SettlementStatus.READY);

        long totalAmount = pendingSettlements.stream()
                .mapToLong(BookingSettlement::getNetToTransportVnd)
                .sum();

        return ResponseEntity.ok(Map.of(
                "settlements", pendingSettlements.stream()
                        .map(s -> Map.of(
                                "settlementId", s.getSettlementId(),
                                "bookingId", s.getBookingId(),
                                "netToTransportVnd", s.getNetToTransportVnd(),
                                "createdAt", s.getCreatedAt(),
                                "readyAt", s.getReadyAt()
                        ))
                        .collect(Collectors.toList()),
                "count", pendingSettlements.size(),
                "totalAmount", totalAmount
        ));
    }

    private User getUserFromAuth(Authentication authentication) {
        return AuthenticationUtils.getUser(authentication, userRepository);
    }

    private TransportPayoutListDTO mapToListDTO(TransportPayout payout) {
        TransportPayoutListDTO dto = new TransportPayoutListDTO();
        dto.setPayoutId(payout.getPayoutId());
        dto.setPayoutNumber(payout.getPayoutNumber());
        dto.setTotalAmountVnd(payout.getTotalAmountVnd());
        dto.setItemCount(payout.getItemCount());
        dto.setStatus(payout.getStatus());
        dto.setCreatedAt(payout.getCreatedAt());
        dto.setProcessedAt(payout.getProcessedAt());
        dto.setCompletedAt(payout.getCompletedAt());
        return dto;
    }
}

