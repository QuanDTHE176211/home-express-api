package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.booking.*;
import com.homeexpress.home_express_api.dto.request.ConfirmCompletionRequest;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.exception.UnauthorizedException;
import com.homeexpress.home_express_api.repository.*;
import com.homeexpress.home_express_api.dto.response.QuotationResponse;
import com.homeexpress.home_express_api.dto.response.BookingTimelineResponse;
import com.homeexpress.home_express_api.dto.response.BookingTimelineEvent;
import com.homeexpress.home_express_api.entity.Notification;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.Quotation;
import com.homeexpress.home_express_api.entity.QuotationStatus;
import com.homeexpress.home_express_api.entity.Payment;
import com.homeexpress.home_express_api.entity.PaymentStatus;
import com.homeexpress.home_express_api.entity.PaymentType;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.map.MapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Map<BookingStatus, EnumSet<BookingStatus>> ALLOWED_STATUS_TRANSITIONS =
            Map.of(
                    BookingStatus.PENDING, EnumSet.of(BookingStatus.QUOTED, BookingStatus.CANCELLED),
                    BookingStatus.QUOTED, EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED)
            );

    private final BookingRepository bookingRepository;

    private final BookingStatusHistoryRepository statusHistoryRepository;

    private final VnProvinceRepository provinceRepository;

    private final VnDistrictRepository districtRepository;

    private final VnWardRepository wardRepository;

    private final CustomerRepository customerRepository;

    private final QuotationRepository quotationRepository;

    private final BookingItemRepository bookingItemRepository;

    private final NotificationService notificationService;

    private final UserRepository userRepository;

    private final TransportRepository transportRepository;

    private final PaymentRepository paymentRepository;

    private final BookingSettlementRepository settlementRepository;

    private final SettlementService settlementService;

    private final CustomerEventService customerEventService;

    private final MapService mapService;

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional
    public BookingResponse createBooking(BookingRequest request, Long customerId, UserRole requesterRole) {
        if (requesterRole != null &&
                requesterRole != UserRole.CUSTOMER &&
                requesterRole != UserRole.MANAGER) {
            throw new UnauthorizedException("Only customers or managers can create bookings");
        }

        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found with ID: " + customerId);
        }

        validateAddress(request.getPickupAddress(), "Pickup");
        validateAddress(request.getDeliveryAddress(), "Delivery");

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setStatus(BookingStatus.PENDING);

        mapAddressToBooking(request.getPickupAddress(), booking, true);
        mapAddressToBooking(request.getDeliveryAddress(), booking, false);

        booking.setPreferredDate(request.getPreferredDate());
        booking.setPreferredTimeSlot(request.getPreferredTimeSlot());
        booking.setNotes(request.getNotes());
        booking.setSpecialRequirements(request.getSpecialRequirements());

        // Set transport ID if provided (when customer selects a transport directly)
        if (request.getTransportId() != null) {
            booking.setTransportId(request.getTransportId());
        }

        // Set estimated price if provided (from transport estimate)
        if (request.getEstimatedPrice() != null) {
            booking.setEstimatedPrice(request.getEstimatedPrice());
        }

        if (request.getPickupAddress().getLat() != null && 
            request.getPickupAddress().getLng() != null &&
            request.getDeliveryAddress().getLat() != null && 
            request.getDeliveryAddress().getLng() != null) {
            calculateDistance(booking, request.getPickupAddress(), request.getDeliveryAddress());
        }

        Booking savedBooking = bookingRepository.save(booking);

        // Save booking items
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (BookingRequest.ItemDto itemDto : request.getItems()) {
                BookingItem item = new BookingItem();
                item.setBookingId(savedBooking.getBookingId());
                item.setCategoryId(itemDto.getCategoryId());
                item.setName(itemDto.getName());
                item.setBrand(itemDto.getBrand());
                item.setModel(itemDto.getModel());
                item.setQuantity(itemDto.getQuantity());
                item.setWeightKg(itemDto.getWeight());
                item.setDeclaredValueVnd(itemDto.getDeclaredValueVnd());
                item.setIsFragile(itemDto.getIsFragile() != null ? itemDto.getIsFragile() : false);
                item.setRequiresDisassembly(itemDto.getRequiresDisassembly() != null ? itemDto.getRequiresDisassembly() : false);
                
                bookingItemRepository.save(item);
            }
        }

        createStatusHistory(savedBooking.getBookingId(), null, BookingStatus.PENDING, 
                           customerId, ActorRole.CUSTOMER, "Booking created");

        // Send notification to customer about booking creation
        sendBookingCreatedNotification(savedBooking, customerId);

        return BookingResponse.fromEntity(savedBooking);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','TRANSPORT')")
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long bookingId, Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to view this booking");
        }

        return BookingResponse.fromEntity(booking);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsForUser(Long customerId, Long requesterId, UserRole requesterRole) {
        if (requesterRole == UserRole.MANAGER) {
            if (customerId != null) {
                return getBookingsByCustomer(customerId, requesterId, requesterRole);
            }
            return getAllBookings(requesterRole);
        }

        if (requesterRole == UserRole.CUSTOMER) {
            Long effectiveCustomerId = customerId != null ? customerId : requesterId;
            return getBookingsByCustomer(effectiveCustomerId, requesterId, requesterRole);
        }

        throw new UnauthorizedException("Access denied");
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByCustomer(Long customerId, Long requestingUserId, UserRole userRole) {
        if (userRole == UserRole.CUSTOMER && !customerId.equals(requestingUserId)) {
            throw new UnauthorizedException("You can only view your own bookings");
        }

        return bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
            .stream()
            .map(BookingResponse::fromEntity)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getAllBookings(UserRole userRole) {
        if (userRole != UserRole.MANAGER) {
            throw new UnauthorizedException("Only managers can view all bookings");
        }

        return bookingRepository.findAll()
            .stream()
            .map(BookingResponse::fromEntity)
            .collect(Collectors.toList());
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional
    public BookingResponse updateBooking(Long bookingId, BookingUpdateRequest request, 
                                        Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to update this booking");
        }

        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.QUOTED) {
            throw new IllegalStateException("Can only update bookings with PENDING or QUOTED status");
        }

        BookingStatus oldStatus = booking.getStatus();

        if (request.getPickupAddress() != null) {
            validateAddress(request.getPickupAddress(), "Pickup");
            mapAddressToBooking(request.getPickupAddress(), booking, true);
        }

        if (request.getDeliveryAddress() != null) {
            validateAddress(request.getDeliveryAddress(), "Delivery");
            mapAddressToBooking(request.getDeliveryAddress(), booking, false);
        }

        if (request.getPreferredDate() != null) {
            booking.setPreferredDate(request.getPreferredDate());
        }

        if (request.getPreferredTimeSlot() != null) {
            booking.setPreferredTimeSlot(request.getPreferredTimeSlot());
        }

        if (request.getNotes() != null) {
            booking.setNotes(request.getNotes());
        }

        if (request.getSpecialRequirements() != null) {
            booking.setSpecialRequirements(request.getSpecialRequirements());
        }

        if (request.getStatus() != null && request.getStatus() != oldStatus) {
            validateStatusTransition(oldStatus, request.getStatus());
            booking.setStatus(request.getStatus());
            
            ActorRole actorRole = userRole == UserRole.CUSTOMER ? ActorRole.CUSTOMER : 
                                 userRole == UserRole.MANAGER ? ActorRole.MANAGER : ActorRole.SYSTEM;
            
            createStatusHistory(bookingId, oldStatus, request.getStatus(), 
                              userId, actorRole, request.getCancellationReason());
            
            // Send notification about status change
            sendBookingStatusChangeNotification(booking, oldStatus, request.getStatus());
        }

        Booking updatedBooking = bookingRepository.save(booking);
        return BookingResponse.fromEntity(updatedBooking);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional
    public void cancelBooking(Long bookingId, String reason, Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to cancel this booking");
        }

        if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel a booking that is already " + booking.getStatus());
        }

        BookingStatus oldStatus = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(userId);
        booking.setCancellationReason(reason);
        booking.setCancelledAt(LocalDateTime.now());

        bookingRepository.save(booking);

        ActorRole actorRole = userRole == UserRole.CUSTOMER ? ActorRole.CUSTOMER : 
                             userRole == UserRole.MANAGER ? ActorRole.MANAGER : ActorRole.SYSTEM;

        createStatusHistory(bookingId, oldStatus, BookingStatus.CANCELLED, 
                          userId, actorRole, reason);
        
        // Send notification about cancellation
        sendBookingStatusChangeNotification(booking, oldStatus, BookingStatus.CANCELLED);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER','TRANSPORT')")
    @Transactional(readOnly = true)
    public List<BookingStatusHistoryResponse> getBookingHistory(Long bookingId, Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to view this booking's history");
        }

        return statusHistoryRepository.findByBookingIdOrderByChangedAtDesc(bookingId)
            .stream()
            .map(BookingStatusHistoryResponse::fromEntity)
            .collect(Collectors.toList());
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional(readOnly = true)
    public List<QuotationResponse> getBookingQuotations(Long bookingId, Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to view this booking's quotations");
        }

        return quotationRepository.findByBookingId(bookingId)
            .stream()
            .map(quotation -> {
                QuotationResponse response = new QuotationResponse();
                response.setQuotationId(quotation.getQuotationId());
                response.setBookingId(quotation.getBookingId());
                response.setTransportId(quotation.getTransportId());
                response.setQuotedPrice(quotation.getQuotedPrice());
                response.setBasePrice(quotation.getBasePrice());
                response.setDistancePrice(quotation.getDistancePrice());
                response.setItemsPrice(quotation.getItemsPrice());
                response.setAdditionalFees(quotation.getAdditionalFees());
                response.setDiscount(quotation.getDiscount());
                response.setPriceBreakdown(quotation.getPriceBreakdown());
                response.setNotes(quotation.getNotes());
                response.setValidityPeriod(quotation.getValidityPeriod());
                response.setExpiresAt(quotation.getExpiresAt());
                response.setStatus(quotation.getStatus());
                response.setRespondedAt(quotation.getRespondedAt());
                response.setAcceptedBy(quotation.getAcceptedBy());
                response.setAcceptedAt(quotation.getAcceptedAt());
                response.setCreatedAt(quotation.getCreatedAt());
                return response;
            })
            .collect(Collectors.toList());
    }

    /**
     * Khách chọn transport sau khi xem bảng giá dự tính.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public BookingResponse assignTransport(Long bookingId, Long transportId, BigDecimal estimatedPrice, Long userId, UserRole userRole) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (userRole == UserRole.CUSTOMER && !booking.getCustomerId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to update this booking");
        }

        if (booking.getTransportId() != null && !booking.getTransportId().equals(transportId)) {
            throw new IllegalStateException("Booking already assigned to another transport");
        }

        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.QUOTED) {
            throw new IllegalStateException("Only PENDING/QUOTED bookings can assign transport");
        }

        Transport transport = transportRepository.findById(transportId)
                .orElseThrow(() -> new ResourceNotFoundException("Transport not found with ID: " + transportId));

        if (transport.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new IllegalStateException("Transport is not approved");
        }
        if (!Boolean.TRUE.equals(transport.getReadyToQuote())) {
            throw new IllegalStateException("Transport has not completed pricing setup (ready_to_quote=false)");
        }

        BookingStatus oldStatus = booking.getStatus();
        booking.setTransportId(transportId);
        if (estimatedPrice != null) {
            booking.setEstimatedPrice(estimatedPrice);
        }

        // Nếu vẫn đang pending thì chuyển sang QUOTED để phản ánh đã chọn đối tác
        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.QUOTED);
            createStatusHistory(bookingId, oldStatus, BookingStatus.QUOTED, userId, ActorRole.CUSTOMER, "Customer selected transport");
        }

        Booking saved = bookingRepository.save(booking);
        return BookingResponse.fromEntity(saved);
    }

    private void validateAddress(AddressDto address, String type) {
        if (address.getProvinceCode() != null && !address.getProvinceCode().isEmpty()) {
            if (!provinceRepository.existsById(address.getProvinceCode())) {
                throw new IllegalArgumentException(type + " address has invalid province code: " + address.getProvinceCode());
            }
        }

        if (address.getDistrictCode() != null && !address.getDistrictCode().isEmpty()) {
            if (!districtRepository.existsById(address.getDistrictCode())) {
                throw new IllegalArgumentException(type + " address has invalid district code: " + address.getDistrictCode());
            }
        }

        if (address.getWardCode() != null && !address.getWardCode().isEmpty()) {
            if (!wardRepository.existsById(address.getWardCode())) {
                throw new IllegalArgumentException(type + " address has invalid ward code: " + address.getWardCode());
            }
        }
    }

    private void mapAddressToBooking(AddressDto address, Booking booking, boolean isPickup) {
        if (isPickup) {
            booking.setPickupAddress(address.getAddressLine());
            booking.setPickupProvinceCode(address.getProvinceCode());
            booking.setPickupDistrictCode(address.getDistrictCode());
            booking.setPickupWardCode(address.getWardCode());
            booking.setPickupLatitude(address.getLat());
            booking.setPickupLongitude(address.getLng());
            booking.setPickupFloor(address.getFloor());
            booking.setPickupHasElevator(address.getHasElevator());
        } else {
            booking.setDeliveryAddress(address.getAddressLine());
            booking.setDeliveryProvinceCode(address.getProvinceCode());
            booking.setDeliveryDistrictCode(address.getDistrictCode());
            booking.setDeliveryWardCode(address.getWardCode());
            booking.setDeliveryLatitude(address.getLat());
            booking.setDeliveryLongitude(address.getLng());
            booking.setDeliveryFloor(address.getFloor());
            booking.setDeliveryHasElevator(address.getHasElevator());
        }
    }

    private void calculateDistance(Booking booking, AddressDto pickup, AddressDto delivery) {
        double lat1 = pickup.getLat().doubleValue();
        double lon1 = pickup.getLng().doubleValue();
        double lat2 = delivery.getLat().doubleValue();
        double lon2 = delivery.getLng().doubleValue();

        try {
            long distanceMeters = mapService.calculateDistanceInMeters(lat1, lon1, lat2, lon2);
            if (distanceMeters > 0) {
                double distanceKm = distanceMeters / 1000.0;
                booking.setDistanceKm(BigDecimal.valueOf(distanceKm));
                booking.setDistanceSource(DistanceSource.GOONG);
                booking.setDistanceCalculatedAt(LocalDateTime.now());
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to calculate distance using MapService: {}", e.getMessage());
        }

        // Fallback to Haversine (MANUAL)
        double distance = haversineDistance(lat1, lon1, lat2, lon2);
        
        booking.setDistanceKm(BigDecimal.valueOf(distance));
        booking.setDistanceSource(DistanceSource.MANUAL);
        booking.setDistanceCalculatedAt(LocalDateTime.now());
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }

    public void validateStatusTransition(BookingStatus oldStatus, BookingStatus newStatus) {
        if (oldStatus == BookingStatus.COMPLETED) {
            throw new IllegalStateException("Cannot change status of a completed booking");
        }
        
        if (oldStatus == BookingStatus.CANCELLED && newStatus != BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot reopen a cancelled booking");
        }

        EnumSet<BookingStatus> allowedNext = ALLOWED_STATUS_TRANSITIONS.get(oldStatus);
        if (allowedNext != null && !allowedNext.contains(newStatus)) {
            throw new IllegalStateException(String.format("Invalid status transition: %s -> %s", oldStatus, newStatus));
        }
    }

    private void createStatusHistory(Long bookingId, BookingStatus oldStatus, BookingStatus newStatus,
                                     Long changedBy, ActorRole role, String reason) {
        BookingStatusHistory history = new BookingStatusHistory(bookingId, oldStatus, newStatus, changedBy, role);
        history.setReason(reason);
        statusHistoryRepository.save(history);
    }

    /**
     * Send notification when booking status changes
     */
    private void sendBookingStatusChangeNotification(Booking booking, BookingStatus oldStatus, BookingStatus newStatus) {
        try {
            // Get customer user
            User customerUser = userRepository.findById(booking.getCustomerId()).orElse(null);
            if (customerUser != null) {
                String statusMessage = getStatusChangeMessage(newStatus);
                notificationService.createNotification(
                        customerUser.getUserId(),
                        Notification.NotificationType.BOOKING_UPDATE,
                        "Booking Status Updated",
                        String.format("Your booking #%d status has changed to %s. %s",
                                booking.getBookingId(), newStatus.name(), statusMessage),
                        Notification.ReferenceType.BOOKING,
                        booking.getBookingId(),
                        getNotificationPriority(newStatus)
                );
            }

            // Notify transport if assigned
            if (booking.getTransportId() != null) {
                transportRepository.findById(booking.getTransportId()).ifPresent(transport -> {
                    if (transport.getUser() != null) {
                        String statusMessage = getStatusChangeMessage(newStatus);
                        notificationService.createNotification(
                                transport.getUser().getUserId(),
                                Notification.NotificationType.BOOKING_UPDATE,
                                "Booking Status Updated",
                                String.format("Booking #%d status has changed to %s. %s",
                                        booking.getBookingId(), newStatus.name(), statusMessage),
                                Notification.ReferenceType.BOOKING,
                                booking.getBookingId(),
                                getNotificationPriority(newStatus)
                        );
                    }
                });
            }

            // Send SSE event for real-time updates
            String oldStatusStr = oldStatus != null ? oldStatus.name() : null;
            String statusMessage = getStatusChangeMessage(newStatus);
            customerEventService.sendBookingStatusUpdate(
                    booking.getBookingId(),
                    oldStatusStr,
                    newStatus.name(),
                    statusMessage
            );

            log.debug("Sent SSE booking status update for booking {}: {} -> {}",
                    booking.getBookingId(), oldStatusStr, newStatus.name());

        } catch (Exception e) {
            // Log error but don't fail the transaction
            log.error("Failed to send booking status change notification for booking {}: {}",
                booking.getBookingId(), e.getMessage(), e);
        }
    }

    /**
     * Send notification when booking is created
     */
    private void sendBookingCreatedNotification(Booking booking, Long customerId) {
        try {
            User customerUser = userRepository.findById(customerId).orElse(null);
            if (customerUser != null) {
                notificationService.createNotification(
                    customerUser.getUserId(),
                    Notification.NotificationType.BOOKING_UPDATE,
                    "Booking Created Successfully",
                    String.format("Your booking #%d has been created and is awaiting quotations from transports.", 
                        booking.getBookingId()),
                    Notification.ReferenceType.BOOKING,
                    booking.getBookingId(),
                    Notification.Priority.MEDIUM
                );
            }
        } catch (Exception e) {
            log.error("Failed to send booking created notification for booking {}: {}", 
                booking.getBookingId(), e.getMessage(), e);
        }
    }

    private String getStatusChangeMessage(BookingStatus status) {
        return switch (status) {
            case PENDING -> "Your booking is pending quotation.";
            case QUOTED -> "Quotation has been submitted. Please review and accept.";
            case CONFIRMED -> "Booking confirmed! Please proceed with payment.";
            case IN_PROGRESS -> "Your booking is now in progress.";
            case COMPLETED -> "Booking completed! Please leave a review.";
            case CANCELLED -> "Booking has been cancelled.";
            default -> "Booking status updated.";
        };
    }

    private Notification.Priority getNotificationPriority(BookingStatus status) {
        return switch (status) {
            case COMPLETED, CANCELLED -> Notification.Priority.HIGH;
            case CONFIRMED, IN_PROGRESS -> Notification.Priority.MEDIUM;
            default -> Notification.Priority.LOW;
        };
    }

    /**
     * Get comprehensive timeline for a booking (admin view)
     * Includes status changes, quotations, and payments
     */
    @Transactional(readOnly = true)
    public BookingTimelineResponse getBookingTimeline(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        java.util.List<BookingTimelineEvent> events = new java.util.ArrayList<>();

        // 1. Add status history events
        List<BookingStatusHistory> statusHistory = statusHistoryRepository.findByBookingIdOrderByChangedAtAsc(bookingId);
        for (BookingStatusHistory history : statusHistory) {
            String actorName = getActorName(history.getChangedBy(), history.getChangedByRole());
            String description = String.format("Status changed from %s to %s", 
                    history.getOldStatus() != null ? history.getOldStatus().name() : "N/A",
                    history.getNewStatus().name());
            if (history.getReason() != null && !history.getReason().isEmpty()) {
                description += ". Reason: " + history.getReason();
            }

            events.add(BookingTimelineEvent.builder()
                    .timestamp(history.getChangedAt())
                    .eventType("STATUS_CHANGE")
                    .status(history.getNewStatus())
                    .actorRole(history.getChangedByRole())
                    .actorId(history.getChangedBy())
                    .actorName(actorName)
                    .description(description)
                    .build());
        }

        // 2. Add quotation events
        List<Quotation> quotations = quotationRepository.findByBookingId(bookingId);
        for (Quotation quotation : quotations) {
            // Quotation submitted
            if (quotation.getCreatedAt() != null) {
                String transportName = getTransportName(quotation.getTransportId());
                events.add(BookingTimelineEvent.builder()
                        .timestamp(quotation.getCreatedAt())
                        .eventType("QUOTATION_SUBMITTED")
                        .status(null)
                        .actorRole(ActorRole.TRANSPORT)
                        .actorId(quotation.getTransportId())
                        .actorName(transportName)
                        .description(String.format("Quotation submitted: %.0f VND", 
                                quotation.getQuotedPrice().doubleValue()))
                        .referenceId(quotation.getQuotationId())
                        .referenceType("QUOTATION")
                        .build());
            }

            // Quotation accepted/rejected
            if (quotation.getAcceptedAt() != null) {
                String customerName = getCustomerName(booking.getCustomerId());
                events.add(BookingTimelineEvent.builder()
                        .timestamp(quotation.getAcceptedAt())
                        .eventType("QUOTATION_ACCEPTED")
                        .status(null)
                        .actorRole(ActorRole.CUSTOMER)
                        .actorId(booking.getCustomerId())
                        .actorName(customerName)
                        .description(String.format("Quotation accepted: %.0f VND", 
                                quotation.getQuotedPrice().doubleValue()))
                        .referenceId(quotation.getQuotationId())
                        .referenceType("QUOTATION")
                        .build());
            } else if (quotation.getRespondedAt() != null && 
                       quotation.getStatus() == QuotationStatus.REJECTED) {
                String customerName = getCustomerName(booking.getCustomerId());
                events.add(BookingTimelineEvent.builder()
                        .timestamp(quotation.getRespondedAt())
                        .eventType("QUOTATION_REJECTED")
                        .status(null)
                        .actorRole(ActorRole.CUSTOMER)
                        .actorId(booking.getCustomerId())
                        .actorName(customerName)
                        .description("Quotation rejected")
                        .referenceId(quotation.getQuotationId())
                        .referenceType("QUOTATION")
                        .build());
            }
        }

        // 3. Add payment events
        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);
        for (Payment payment : payments) {
            // Payment initiated
            if (payment.getCreatedAt() != null) {
                String customerName = getCustomerName(booking.getCustomerId());
                String paymentTypeStr = payment.getPaymentType() == PaymentType.DEPOSIT ? "Deposit" : "Payment";
                events.add(BookingTimelineEvent.builder()
                        .timestamp(payment.getCreatedAt())
                        .eventType("PAYMENT_INITIATED")
                        .status(null)
                        .actorRole(ActorRole.CUSTOMER)
                        .actorId(booking.getCustomerId())
                        .actorName(customerName)
                        .description(String.format("%s initiated: %.0f VND (%s)", 
                                paymentTypeStr,
                                payment.getAmount().doubleValue(),
                                payment.getPaymentMethod().name()))
                        .referenceId(payment.getPaymentId())
                        .referenceType("PAYMENT")
                        .build());
            }

            // Payment completed
            if (payment.getPaidAt() != null && payment.getStatus() == PaymentStatus.COMPLETED) {
                String customerName = getCustomerName(booking.getCustomerId());
                String paymentTypeStr = payment.getPaymentType() == PaymentType.DEPOSIT ? "Deposit" : "Payment";
                events.add(BookingTimelineEvent.builder()
                        .timestamp(payment.getPaidAt())
                        .eventType("PAYMENT_COMPLETED")
                        .status(null)
                        .actorRole(ActorRole.CUSTOMER)
                        .actorId(booking.getCustomerId())
                        .actorName(customerName)
                        .description(String.format("%s completed: %.0f VND", 
                                paymentTypeStr,
                                payment.getAmount().doubleValue()))
                        .referenceId(payment.getPaymentId())
                        .referenceType("PAYMENT")
                        .build());
            }
        }

        // Sort all events by timestamp
        events.sort((e1, e2) -> e1.getTimestamp().compareTo(e2.getTimestamp()));

        return BookingTimelineResponse.builder()
                .bookingId(bookingId)
                .timeline(events)
                .totalEvents(events.size())
                .build();
    }

    private String getActorName(Long userId, ActorRole role) {
        if (userId == null) {
            return "System";
        }
        try {
            if (role == ActorRole.CUSTOMER) {
                return customerRepository.findById(userId)
                        .map(c -> c.getFullName())
                        .orElse(userRepository.findById(userId).map(u -> u.getEmail()).orElse("Unknown"));
            } else if (role == ActorRole.TRANSPORT) {
                return transportRepository.findById(userId)
                        .map(t -> t.getCompanyName())
                        .orElse(userRepository.findById(userId).map(u -> u.getEmail()).orElse("Unknown"));
            }
            
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                return user.getEmail();
            }
        } catch (Exception e) {
            // Ignore
        }
        return role != null ? role.name() : "Unknown";
    }

    private String getTransportName(Long transportId) {
        if (transportId == null) {
            return "Unknown Transport";
        }
        try {
            return transportRepository.findById(transportId)
                    .map(t -> t.getCompanyName() != null ? t.getCompanyName() : "Transport #" + transportId)
                    .orElse("Unknown Transport");
        } catch (Exception e) {
            return "Transport #" + transportId;
        }
    }

    private String getCustomerName(Long customerId) {
        if (customerId == null) {
            return "Unknown Customer";
        }
        try {
            return customerRepository.findById(customerId)
                    .map(c -> c.getFullName())
                    .orElse(userRepository.findById(customerId).map(u -> u.getEmail()).orElse("Customer #" + customerId));
        } catch (Exception e) {
            // Ignore
        }
        return "Customer #" + customerId;
    }

    /**
     * Customer confirms booking completion after remaining payment is made
     * Updates booking status to CONFIRMED_BY_CUSTOMER and settlement to READY
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public BookingResponse confirmBookingCompletion(Long bookingId, ConfirmCompletionRequest request, Long customerId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        // 1. Validate customer owns the booking
        if (!booking.getCustomerId().equals(customerId)) {
            throw new UnauthorizedException("You can only confirm completion for your own bookings");
        }

        // 2. Validate booking status is COMPLETED (allow idempotent confirm)
        if (booking.getStatus() != BookingStatus.COMPLETED
                && booking.getStatus() != BookingStatus.CONFIRMED_BY_CUSTOMER) {
            throw new IllegalStateException("Booking must be in COMPLETED status to confirm completion. Current status: " + booking.getStatus());
        }
        boolean alreadyConfirmed = booking.getStatus() == BookingStatus.CONFIRMED_BY_CUSTOMER;

        // 3. Verify remaining payment has been made
        List<Payment> payments = paymentRepository.findByBookingId(bookingId);
        boolean remainingPaymentCompleted = payments.stream()
                .anyMatch(p -> PaymentType.REMAINING_PAYMENT.equals(p.getPaymentType())
                        && PaymentStatus.COMPLETED.equals(p.getStatus()));

        if (!remainingPaymentCompleted) {
            throw new IllegalStateException("Remaining payment must be completed before confirming booking completion");
        }

        // 4. Update booking status (idempotent if already confirmed)
        Booking updatedBooking = booking;
        if (!alreadyConfirmed) {
            BookingStatus oldStatus = booking.getStatus();
            booking.setStatus(BookingStatus.CONFIRMED_BY_CUSTOMER);
            updatedBooking = bookingRepository.save(booking);

            // 5. Create status history
            createStatusHistory(bookingId, oldStatus, BookingStatus.CONFIRMED_BY_CUSTOMER,
                    customerId, ActorRole.CUSTOMER, request != null ? request.getFeedback() : null);
        }

        // 6. Update settlement status to READY
        // In the new flow (Escrow), we trigger settlement processing here
        // instead of just setting status to READY.
        try {
             settlementService.processSettlement(bookingId);
             log.info("Settlement processed successfully for booking {}", bookingId);
        } catch (Exception e) {
             log.error("Failed to process settlement for booking {}: {}", bookingId, e.getMessage());
             // Don't fail the confirmation if settlement fails, but log it
        }

        // 7. Send notifications
        sendCompletionConfirmationNotifications(updatedBooking, request);

        return BookingResponse.fromEntity(updatedBooking);
    }

    /**
     * Send notifications when customer confirms booking completion
     */
    private void sendCompletionConfirmationNotifications(Booking booking, ConfirmCompletionRequest request) {
        try {
            // Notify customer
            notificationService.createNotification(
                    booking.getCustomerId(),
                    Notification.NotificationType.BOOKING_UPDATE,
                    "Booking Completion Confirmed",
                    String.format("Thank you for confirming completion of booking #%d. Your feedback is appreciated!",
                            booking.getBookingId()),
                    Notification.ReferenceType.BOOKING,
                    booking.getBookingId(),
                    Notification.Priority.MEDIUM
            );

            // Notify transport
            if (booking.getTransportId() != null) {
                transportRepository.findById(booking.getTransportId()).ifPresent(transport -> {
                    if (transport.getUser() != null) {
                        String feedbackMsg = (request != null && request.getFeedback() != null)
                                ? " Customer feedback: " + request.getFeedback()
                                : "";
                        notificationService.createNotification(
                                transport.getUser().getUserId(),
                                Notification.NotificationType.BOOKING_UPDATE,
                                "Customer Confirmed Completion",
                                String.format("Customer has confirmed completion of booking #%d. Payment will be processed soon.%s",
                                        booking.getBookingId(), feedbackMsg),
                                Notification.ReferenceType.BOOKING,
                                booking.getBookingId(),
                                Notification.Priority.HIGH
                        );
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to send completion confirmation notifications for booking {}: {}",
                    booking.getBookingId(), e.getMessage());
        }
    }
}
