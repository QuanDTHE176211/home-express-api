package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.booking.BookingItemSummaryResponse;
import com.homeexpress.home_express_api.dto.response.ApiResponse;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.BookingItemService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller responsible for booking item persistence operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/items")
@RequiredArgsConstructor
public class BookingItemController {

    private final BookingItemService bookingItemService;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','TRANSPORT','MANAGER')")
    public ResponseEntity<ApiResponse<List<BookingItemSummaryResponse>>> getBookingItems(
        @PathVariable Long bookingId,
        Authentication authentication
    ) {
        try {
            User user = AuthenticationUtils.getUser(authentication, userRepository);

            Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

            if (user.getRole() == UserRole.CUSTOMER && !booking.getCustomerId().equals(user.getUserId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("You are not allowed to view this booking"));
            }

            if (user.getRole() == UserRole.TRANSPORT) {
                Long bookingTransportId = booking.getTransportId();
                if (bookingTransportId == null || !bookingTransportId.equals(user.getUserId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error("You are not allowed to view this booking"));
                }
            }

            List<BookingItemSummaryResponse> items = bookingItemService.getBookingItems(bookingId).stream()
                .map(BookingItemSummaryResponse::fromEntity)
                .toList();

            return ResponseEntity.ok(ApiResponse.success(items));
        } catch (ResourceNotFoundException ex) {
            log.warn("Failed to load items for booking {}: {}", bookingId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error while loading booking {} items: {}", bookingId, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Failed to load booking items"));
        }
    }
}
