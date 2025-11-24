package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.booking.*;
import com.homeexpress.home_express_api.dto.request.ConfirmCompletionRequest;
import com.homeexpress.home_express_api.dto.request.UploadBookingEvidenceRequest;
import com.homeexpress.home_express_api.dto.response.BookingEvidenceResponse;
import com.homeexpress.home_express_api.dto.response.QuotationResponse;
import com.homeexpress.home_express_api.entity.EvidenceType;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.BookingService;
import com.homeexpress.home_express_api.service.EvidenceService;
import com.homeexpress.home_express_api.service.booking.BookingEstimateService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    private final UserRepository userRepository;

    private final EvidenceService evidenceService;

    private final BookingEstimateService bookingEstimateService;

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<?> createBooking(
            @Valid @RequestBody BookingRequest request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingResponse response = bookingService.createBooking(request, user.getUserId(), user.getRole());
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "message", "Booking created successfully",
            "booking", response
        ));
    }

    @PreAuthorize("hasAnyRole('MANAGER','CUSTOMER')")
    @GetMapping
    public ResponseEntity<?> getBookings(
            @RequestParam(required = false) Long customerId,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);

        List<BookingResponse> bookings =
                bookingService.getBookingsForUser(customerId, user.getUserId(), user.getRole());

        return ResponseEntity.ok(Map.of(
            "bookings", bookings,
            "count", bookings.size()
        ));
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','TRANSPORT')")
    @GetMapping("/{id}")
    public ResponseEntity<?> getBookingById(
            @PathVariable Long id,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingResponse booking = bookingService.getBookingById(id, user.getUserId(), user.getRole());
        
        return ResponseEntity.ok(booking);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<?> updateBooking(
            @PathVariable Long id,
            @Valid @RequestBody BookingUpdateRequest request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingResponse response = bookingService.updateBooking(id, request, user.getUserId(), user.getRole());
        
        return ResponseEntity.ok(Map.of(
            "message", "Booking updated successfully",
            "booking", response
        ));
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelBooking(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);

        String reason = body != null ? body.get("reason") : null;
        
        bookingService.cancelBooking(id, reason, user.getUserId(), user.getRole());
        
        return ResponseEntity.ok(Map.of(
            "message", "Booking cancelled successfully",
            "bookingId", id
        ));
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','TRANSPORT')")
    @GetMapping("/{id}/history")
    public ResponseEntity<?> getBookingHistory(
    @PathVariable Long id,
    Authentication authentication) {

    User user = AuthenticationUtils.getUser(authentication, userRepository);

    List<BookingStatusHistoryResponse> history =
    bookingService.getBookingHistory(id, user.getUserId(), user.getRole());

    return ResponseEntity.ok(Map.of(
    "bookingId", id,
    "history", history,
    "count", history.size()
    ));
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @GetMapping("/{id}/quotations")
    public ResponseEntity<?> getBookingQuotations(
            @PathVariable Long id,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        // Check if user has access to this booking
        bookingService.getBookingById(id, user.getUserId(), user.getRole());

        List<QuotationResponse> quotations = bookingService.getBookingQuotations(id, user.getUserId(), user.getRole());

        return ResponseEntity.ok(Map.of(
            "bookingId", id,
            "quotations", quotations,
            "count", quotations.size()
        ));
    }

    /**
     * Khách chọn transport sau khi xem bảng giá dự tính
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PutMapping("/{id}/assign-transport")
    public ResponseEntity<?> assignTransport(
            @PathVariable Long id,
            @Valid @RequestBody AssignTransportRequest request,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingResponse response = bookingService.assignTransport(
                id,
                request.getTransportId(),
                request.getEstimatedPrice(),
                user.getUserId(),
                user.getRole());

        return ResponseEntity.ok(Map.of(
                "message", "Transport assigned",
                "booking", response
        ));
    }

    /**
     * Khách xem bảng giá dự tính dựa trên rate card của các transport
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{id}/estimates")
    public ResponseEntity<?> getBookingEstimates(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(defaultValue = "price") String sort,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(required = false) Double ratingGte,
            @RequestParam(required = false) Long priceCap,
            @RequestParam(required = false) String vehicleType,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        // Chuẩn hóa query (page/size không âm)
        BookingEstimateService.Query query = new BookingEstimateService.Query();
        query.page = page != null && page > 0 ? page : 1;
        query.size = size != null && size > 0 && size <= 50 ? size : 10;
        query.sort = sort;
        query.order = order;
        query.ratingGte = ratingGte;
        query.priceCap = priceCap;
        query.vehicleType = vehicleType;

        var estimates = bookingEstimateService.estimateForBooking(id, user.getUserId(), user.getRole(), query);

        return ResponseEntity.ok(Map.of(
                "bookingId", id,
                "estimates", estimates.getEstimates(),
                "summary", estimates.getSummary(),
                "pagination", estimates.getPagination(),
                "applied_filters", estimates.getAppliedFilters()
        ));
    }

    /**
     * Customer confirms booking completion after service is done
     * This triggers status change to CONFIRMED_BY_CUSTOMER and settlement becomes READY
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{id}/confirm-completion")
    public ResponseEntity<?> confirmCompletion(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ConfirmCompletionRequest request,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingResponse response = bookingService.confirmBookingCompletion(id, request, user.getUserId());

        return ResponseEntity.ok(Map.of(
            "message", "Booking completion confirmed successfully",
            "booking", response
        ));
    }

    // ========================================================================
    // EVIDENCE ENDPOINTS
    // ========================================================================

    /**
     * Get all evidence for a booking
     * Supports filtering by evidence type
     */
    @PreAuthorize("hasAnyRole('CUSTOMER','TRANSPORT','MANAGER')")
    @GetMapping("/{bookingId}/evidence")
    public ResponseEntity<?> getBookingEvidence(
            @PathVariable Long bookingId,
            @RequestParam(required = false) EvidenceType type,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        List<BookingEvidenceResponse> evidence = evidenceService.getBookingEvidence(
                bookingId,
                user.getUserId(),
                user.getRole(),
                type
        );

        return ResponseEntity.ok(Map.of(
                "bookingId", bookingId,
                "evidence", evidence,
                "count", evidence.size()
        ));
    }

    /**
     * Upload evidence for a booking
     * Customers and transport providers can upload evidence
     */
    @PreAuthorize("hasAnyRole('CUSTOMER','TRANSPORT')")
    @PostMapping("/{bookingId}/evidence")
    public ResponseEntity<?> uploadBookingEvidence(
            @PathVariable Long bookingId,
            @Valid @RequestBody UploadBookingEvidenceRequest request,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        BookingEvidenceResponse response = evidenceService.uploadBookingEvidence(
                bookingId,
                request,
                user.getUserId(),
                user.getRole()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Evidence uploaded successfully",
                "evidence", response
        ));
    }

    /**
     * Delete evidence
     * Only the uploader or managers can delete evidence
     */
    @PreAuthorize("hasAnyRole('CUSTOMER','TRANSPORT','MANAGER')")
    @DeleteMapping("/evidence/{evidenceId}")
    public ResponseEntity<?> deleteEvidence(
            @PathVariable Long evidenceId,
            Authentication authentication) {

        User user = AuthenticationUtils.getUser(authentication, userRepository);

        evidenceService.deleteEvidence(evidenceId, user.getUserId(), user.getRole());

        return ResponseEntity.ok(Map.of(
                "message", "Evidence deleted successfully"
        ));
    }

}

