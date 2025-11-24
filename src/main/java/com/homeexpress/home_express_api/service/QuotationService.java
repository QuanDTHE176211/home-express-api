package com.homeexpress.home_express_api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.request.QuotationRequest;
import com.homeexpress.home_express_api.dto.request.SubmitQuotationRequest;
import com.homeexpress.home_express_api.dto.response.AcceptQuotationResponse;
import com.homeexpress.home_express_api.dto.response.QuotationDetailResponse;
import com.homeexpress.home_express_api.dto.response.QuotationResponse;
import com.homeexpress.home_express_api.dto.response.SuggestedPriceResponse;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingItem;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.Contract;
import com.homeexpress.home_express_api.entity.ContractStatus;
import com.homeexpress.home_express_api.entity.Customer;
import com.homeexpress.home_express_api.entity.Quotation;
import com.homeexpress.home_express_api.entity.QuotationStatus;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.repository.BookingItemRepository;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.ContractRepository;
import com.homeexpress.home_express_api.repository.CustomerRepository;
import com.homeexpress.home_express_api.repository.QuotationRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.repository.VehicleRepository;
import com.homeexpress.home_express_api.constants.BookingConstants;
import com.homeexpress.home_express_api.entity.Notification;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.exception.QuotationNotFoundException;
import com.homeexpress.home_express_api.exception.InvalidQuotationStatusException;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    private final QuotationRepository quotationRepository;
    private final BookingRepository bookingRepository;
    private final ContractRepository contractRepository;
    private final CustomerRepository customerRepository;
    private final TransportRepository transportRepository;
    private final VehicleRepository vehicleRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final BookingItemRepository bookingItemRepository;
    private final CustomerEventService customerEventService;
    private final RateCardService rateCardService;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;

    public QuotationService(QuotationRepository quotationRepository,
            BookingRepository bookingRepository,
            ContractRepository contractRepository,
            CustomerRepository customerRepository,
            TransportRepository transportRepository,
            VehicleRepository vehicleRepository,
            NotificationService notificationService,
            UserRepository userRepository,
            BookingItemRepository bookingItemRepository,
            CustomerEventService customerEventService,
            RateCardService rateCardService,
            PricingService pricingService,
            ObjectMapper objectMapper) {
        this.quotationRepository = quotationRepository;
        this.bookingRepository = bookingRepository;
        this.contractRepository = contractRepository;
        this.customerRepository = customerRepository;
        this.transportRepository = transportRepository;
        this.vehicleRepository = vehicleRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.customerEventService = customerEventService;
        this.rateCardService = rateCardService;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuotationResponse submitQuotation(SubmitQuotationRequest request, Long transportId) {
        Booking booking = bookingRepository.findById(request.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", request.getBookingId()));

        validateBookingReadyForQuotation(booking);

        if (quotationRepository.existsByBookingIdAndTransportId(booking.getBookingId(), transportId)) {
            throw new IllegalStateException("Transport provider has already submitted a quotation for this booking");
        }

        Quotation quotation = new Quotation();
        quotation.setBookingId(request.getBookingId());
        quotation.setTransportId(transportId);
        quotation.setVehicleId(request.getVehicleId());

        // Calculate total quoted price
        BigDecimal totalPrice = request.getBasePrice()
                .add(request.getDistancePrice() != null ? request.getDistancePrice() : BigDecimal.ZERO)
                .add(request.getItemHandlingPrice() != null ? request.getItemHandlingPrice() : BigDecimal.ZERO)
                .add(request.getAdditionalServicesPrice() != null ? request.getAdditionalServicesPrice() : BigDecimal.ZERO);

        quotation.setQuotedPrice(totalPrice);
        quotation.setBasePrice(request.getBasePrice());
        quotation.setDistancePrice(request.getDistancePrice());
        quotation.setItemsPrice(request.getItemHandlingPrice());
        quotation.setAdditionalFees(request.getAdditionalServicesPrice());

        // Calculate suggested price using dynamic rate card pricing
        SuggestedPriceResponse suggestedPrice = null;
        BigDecimal suggestedTotal = null;
        Double deviationPercent = null;
        try {
            suggestedPrice = pricingService.calculateSuggestedPrice(booking.getBookingId(), transportId);
            if (suggestedPrice != null && suggestedPrice.getSuggestedPrice() != null
                    && suggestedPrice.getSuggestedPrice().compareTo(BigDecimal.ZERO) > 0) {
                suggestedTotal = suggestedPrice.getSuggestedPrice();
                BigDecimal diff = totalPrice.subtract(suggestedTotal);
                BigDecimal deviationFraction = diff.divide(suggestedTotal, 4, RoundingMode.HALF_UP);
                deviationPercent = deviationFraction.multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP)
                        .doubleValue();
            }
        } catch (Exception e) {
            log.warn("Failed to calculate suggested price for booking {} and transport {}: {}",
                    booking.getBookingId(), transportId, e.getMessage());
        }

        // Build price breakdown JSON (including suggested price metadata when available)
        String priceBreakdown = null;
        try {
            java.util.Map<String, Object> breakdown = new java.util.HashMap<>();
            breakdown.put("basePrice", request.getBasePrice());
            breakdown.put("distancePrice", request.getDistancePrice());
            breakdown.put("itemHandlingPrice", request.getItemHandlingPrice());
            breakdown.put("additionalServicesPrice", request.getAdditionalServicesPrice());
            breakdown.put("includesPackaging", request.getIncludesPackaging());
            breakdown.put("includesDisassembly", request.getIncludesDisassembly());
            breakdown.put("includesInsurance", request.getIncludesInsurance());
            breakdown.put("insuranceValue", request.getInsuranceValue());
            breakdown.put("estimatedDurationHours", request.getEstimatedDurationHours());
            if (suggestedPrice != null) {
                breakdown.put("suggestedPrice", suggestedPrice.getSuggestedPrice());
                breakdown.put("suggestedPriceBreakdown", suggestedPrice.getPriceBreakdown());
                breakdown.put("suggestedRateCardId", suggestedPrice.getRateCardId());
                breakdown.put("suggestedCategoryId", suggestedPrice.getCategoryId());
                breakdown.put("suggestedCalculationTimestamp", suggestedPrice.getCalculationTimestamp());
                if (deviationPercent != null) {
                    breakdown.put("quotedVsSuggestedDeviationPercent", deviationPercent);
                }
            }
            priceBreakdown = objectMapper.writeValueAsString(breakdown);
        } catch (Exception e) {
            log.warn("Failed to serialize price breakdown for booking {}: {}", booking.getBookingId(), e.getMessage());
        }
        quotation.setPriceBreakdown(priceBreakdown);

        quotation.setNotes(request.getNotes());
        quotation.setValidityPeriod(BookingConstants.DEFAULT_QUOTATION_VALIDITY_DAYS);
        quotation.setExpiresAt(LocalDateTime.now().plusDays(BookingConstants.DEFAULT_QUOTATION_EXPIRY_DAYS));
        quotation.setStatus(QuotationStatus.PENDING);

        Quotation saved = quotationRepository.save(quotation);

        if (deviationPercent != null && Math.abs(deviationPercent) > 30.0 && suggestedTotal != null) {
            log.warn("Quotation {} deviates from suggested price by {}% (quoted={}, suggested={})",
                    saved.getQuotationId(), deviationPercent, totalPrice, suggestedTotal);
        }

        // Capture rate card snapshot for audit
        Long categoryId = resolvePrimaryCategoryIdForBooking(booking.getBookingId());
        try {
            rateCardService.captureRateCardSnapshotForQuotation(saved.getQuotationId(), transportId, categoryId);
        } catch (Exception e) {
            log.warn("Failed to capture rate card snapshot for quotation {}: {}", saved.getQuotationId(), e.getMessage());
        }

        // Send notification to customer about new quotation
        sendNewQuotationNotification(saved, booking);

        // Send SSE event for real-time updates
        Transport transport = transportRepository.findById(transportId).orElse(null);
        if (transport != null) {
            customerEventService.sendNewQuotation(
                    booking.getBookingId(),
                    saved.getQuotationId(),
                    transportId,
                    transport.getCompanyName(),
                    saved.getQuotedPrice().longValue()
            );

            log.debug("Sent SSE new quotation event for booking {}, quotation {}",
                    booking.getBookingId(), saved.getQuotationId());
        }

        return mapToResponse(saved);
    }

    @Transactional
    public QuotationResponse createQuotation(QuotationRequest request, Long transportId) {
        Quotation quotation = new Quotation();
        quotation.setBookingId(request.getBookingId());
        quotation.setTransportId(transportId);
        quotation.setVehicleId(request.getVehicleId());
        quotation.setQuotedPrice(request.getQuotedPrice());
        quotation.setBasePrice(request.getBasePrice());
        quotation.setDistancePrice(request.getDistancePrice());
        quotation.setItemsPrice(request.getItemsPrice());
        quotation.setAdditionalFees(request.getAdditionalFees());
        quotation.setDiscount(request.getDiscount());
        quotation.setPriceBreakdown(request.getPriceBreakdown());
        quotation.setNotes(request.getNotes());
        quotation.setValidityPeriod(request.getValidityPeriod() != null ? request.getValidityPeriod() : BookingConstants.DEFAULT_QUOTATION_VALIDITY_DAYS);

        if (request.getExpiresAt() != null) {
            quotation.setExpiresAt(request.getExpiresAt());
        } else {
            quotation.setExpiresAt(LocalDateTime.now().plusDays(quotation.getValidityPeriod()));
        }

        quotation.setStatus(QuotationStatus.PENDING);

        Quotation saved = quotationRepository.save(quotation);

        // Capture rate card snapshot for audit
        Long categoryId = resolvePrimaryCategoryIdForBooking(request.getBookingId());
        try {
            rateCardService.captureRateCardSnapshotForQuotation(saved.getQuotationId(), transportId, categoryId);
        } catch (Exception e) {
            log.warn("Failed to capture rate card snapshot for quotation {}: {}", saved.getQuotationId(), e.getMessage());
        }

        // Send notification to customer about new quotation
        bookingRepository.findById(request.getBookingId())
            .ifPresent(booking -> sendNewQuotationNotification(saved, booking));

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public QuotationResponse getQuotationById(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        return mapToResponse(quotation);
    }

    @Transactional(readOnly = true)
    public Page<QuotationDetailResponse> getDetailedQuotations(Long bookingId, Long transportId, QuotationStatus status, Pageable pageable) {
        Page<Quotation> quotations = getQuotationsPage(bookingId, transportId, status, pageable);
        return quotations.map(this::mapToDetailResponse);
    }

    @Transactional(readOnly = true)
    public Page<QuotationResponse> getQuotations(Long bookingId, Long transportId, QuotationStatus status, Pageable pageable) {
    Page<Quotation> quotations = getQuotationsPage(bookingId, transportId, status, pageable);
        return quotations.map(this::mapToResponse);
    }

    private Page<Quotation> getQuotationsPage(Long bookingId, Long transportId, QuotationStatus status, Pageable pageable) {
    Page<Quotation> quotations;

    if (bookingId != null && status != null) {
        quotations = quotationRepository.findByBookingIdAndStatus(bookingId, status, pageable);
    } else if (transportId != null && status != null) {
        quotations = quotationRepository.findByTransportIdAndStatus(transportId, status, pageable);
    } else if (bookingId != null) {
        quotations = quotationRepository.findByBookingId(bookingId, pageable);
    } else if (transportId != null) {
        quotations = quotationRepository.findByTransportId(transportId, pageable);
        } else if (status != null) {
        quotations = quotationRepository.findByStatus(status, pageable);
        } else {
            quotations = quotationRepository.findAll(pageable);
        }

        return quotations;
    }

    private void validateBookingReadyForQuotation(Booking booking) {
        EnumSet<BookingStatus> allowedStatuses = EnumSet.of(BookingStatus.PENDING, BookingStatus.QUOTED);
        if (!allowedStatuses.contains(booking.getStatus())) {
            throw new IllegalStateException(
                    "Booking is not ready for quotations. Current status: " + booking.getStatus());
        }

        if (!StringUtils.hasText(booking.getPickupAddress()) || !StringUtils.hasText(booking.getDeliveryAddress())) {
            throw new IllegalStateException("Booking is missing pickup or delivery address information");
        }

        if (booking.getPreferredDate() == null) {
            throw new IllegalStateException("Booking is missing preferred move date");
        }

        long itemCount = bookingItemRepository.countByBookingId(booking.getBookingId());
        if (itemCount == 0) {
            throw new IllegalStateException("Booking has no inventory items. Complete intake before requesting quotations.");
        }
    }

    private Long resolvePrimaryCategoryIdForBooking(Long bookingId) {
        List<BookingItem> items = bookingItemRepository.findByBookingId(bookingId);
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (BookingItem item : items) {
            if (item.getCategoryId() != null) {
                return item.getCategoryId();
            }
        }
        return null;
    }

    @Transactional
    public AcceptQuotationResponse acceptQuotation(Long quotationId, Long customerId, String ipAddress) {
        quotationRepository.acceptQuotation(quotationId, customerId, ipAddress);

        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        Booking booking = bookingRepository.findById(quotation.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", quotation.getBookingId()));

        // Auto-create contract if it doesn't exist
        Contract contract = contractRepository.findByBookingId(booking.getBookingId())
                .orElseGet(() -> createContractFromQuotation(quotation, booking));

        AcceptQuotationResponse response = new AcceptQuotationResponse();
        response.setMessage("Quotation accepted successfully");
        populateContractInfo(response, contract);

        // Populate booking summary
        AcceptQuotationResponse.BookingSummary bookingSummary = new AcceptQuotationResponse.BookingSummary();
        bookingSummary.setBookingId(booking.getBookingId());
        bookingSummary.setStatus(booking.getStatus());
        bookingSummary.setFinalTransportId(booking.getTransportId());
        bookingSummary.setFinalPrice(booking.getFinalPrice());
        response.setBooking(bookingSummary);

        // Populate customer metadata
        Customer customer = customerRepository.findById(booking.getCustomerId())
            .orElse(null);
        if (customer != null) {
            AcceptQuotationResponse.CustomerSummary customerSummary = new AcceptQuotationResponse.CustomerSummary();
            customerSummary.setCustomerId(customer.getCustomerId());
            customerSummary.setFullName(customer.getFullName());
            customerSummary.setPhone(customer.getPhone());
            // Note: Customer entity doesn't have email field, need to get from User
            customerSummary.setEmail(""); // Will be populated from User if needed
            customerSummary.setAverageRating(0.0); // Will be calculated if reviews exist
            response.setCustomer(customerSummary);
        }

        // Populate transport metadata
        Transport transport = transportRepository.findById(quotation.getTransportId())
            .orElse(null);
        if (transport != null) {
            AcceptQuotationResponse.TransportSummary transportSummary = new AcceptQuotationResponse.TransportSummary();
            transportSummary.setTransportId(transport.getTransportId());
            transportSummary.setCompanyName(transport.getCompanyName());
            transportSummary.setPhone(transport.getPhone());
            // Note: Transport entity doesn't have email field directly, need to get from User
            transportSummary.setEmail(""); // Will be populated from User if needed
            transportSummary.setAverageRating(transport.getAverageRating() != null ? transport.getAverageRating().doubleValue() : 0.0);
            transportSummary.setTotalBookings(transport.getTotalBookings());
            transportSummary.setCompletedBookings(transport.getCompletedBookings());
            response.setTransport(transportSummary);
        }

        // Send notifications
        sendQuotationAcceptedNotifications(quotation, booking, customer, transport);

        // Send SSE event for transport assignment (quotation acceptance assigns transport)
        if (transport != null) {
            customerEventService.sendTransportAssignment(
                    booking.getBookingId(),
                    transport.getTransportId(),
                    transport.getCompanyName(),
                    transport.getPhone()
            );

            log.debug("Sent SSE transport assignment event for booking {}, transport {}",
                    booking.getBookingId(), transport.getTransportId());
        }

        return response;
    }

    /**
     * Send notifications when quotation is accepted
     */
    private void sendQuotationAcceptedNotifications(Quotation quotation, Booking booking, 
                                                    Customer customer, Transport transport) {
        try {
            // Notify customer
            if (customer != null) {
                User customerUser = userRepository.findById(customer.getCustomerId()).orElse(null);
                if (customerUser != null) {
                    notificationService.createNotification(
                            customerUser.getUserId(),
                            Notification.NotificationType.BOOKING_UPDATE,
                            "Quotation Accepted",
                            String.format("Your booking #%d quotation has been accepted. Contract is ready for signing.", 
                                    booking.getBookingId()),
                            Notification.ReferenceType.BOOKING,
                            booking.getBookingId(),
                            Notification.Priority.HIGH
                    );
                }
            }

            // Notify transport
            if (transport != null && transport.getUser() != null) {
                notificationService.createNotification(
                        transport.getUser().getUserId(),
                        Notification.NotificationType.BOOKING_UPDATE,
                        "Quotation Accepted by Customer",
                        String.format("Your quotation for booking #%d has been accepted by the customer.", 
                                booking.getBookingId()),
                        Notification.ReferenceType.QUOTATION,
                        quotation.getQuotationId(),
                        Notification.Priority.HIGH
                );
            }
        } catch (Exception e) {
            // Log error but don't fail the transaction
            log.error("Failed to send quotation accepted notifications for quotation {}: {}", 
                quotation.getQuotationId(), e.getMessage(), e);
        }
    }

    /**
     * Send notification to customer when new quotation is received
     */
    private void sendNewQuotationNotification(Quotation quotation, Booking booking) {
        try {
            User customerUser = userRepository.findById(booking.getCustomerId()).orElse(null);
            if (customerUser != null) {
                notificationService.createNotification(
                    customerUser.getUserId(),
                    Notification.NotificationType.QUOTATION_RECEIVED,
                    "New Quotation Received",
                    String.format("You have received a new quotation for booking #%d. Amount: %,.0f VND",
                        booking.getBookingId(), quotation.getQuotedPrice()),
                    Notification.ReferenceType.QUOTATION,
                    quotation.getQuotationId(),
                    Notification.Priority.MEDIUM
                );
            }
        } catch (Exception e) {
            log.error("Failed to send quotation received notification for quotation {}: {}",
                quotation.getQuotationId(), e.getMessage(), e);
        }
    }

    /**
     * Send notification to customer when new quotation is received (legacy method)
     */
    private void sendQuotationReceivedNotification(Quotation quotation) {
        try {
            Booking booking = bookingRepository.findById(quotation.getBookingId())
                .orElse(null);

            if (booking != null) {
                sendNewQuotationNotification(quotation, booking);
            }
        } catch (Exception e) {
            log.error("Failed to send quotation received notification for quotation {}: {}",
                quotation.getQuotationId(), e.getMessage(), e);
        }
    }

    /**
     * Send notification to transport when quotation is rejected
     */
    private void sendQuotationRejectedNotification(Quotation quotation) {
        try {
            Transport transport = transportRepository.findById(quotation.getTransportId())
                .orElse(null);
            
            if (transport != null && transport.getUser() != null) {
                notificationService.createNotification(
                    transport.getUser().getUserId(),
                    Notification.NotificationType.QUOTATION_RECEIVED,
                    "Quotation Rejected",
                    String.format("Your quotation #%d for booking #%d has been rejected by the customer.", 
                        quotation.getQuotationId(), quotation.getBookingId()),
                    Notification.ReferenceType.QUOTATION,
                    quotation.getQuotationId(),
                    Notification.Priority.LOW
                );
            }
        } catch (Exception e) {
            log.error("Failed to send quotation rejected notification for quotation {}: {}", 
                quotation.getQuotationId(), e.getMessage(), e);
        }
    }

    @Transactional
    public QuotationResponse rejectQuotation(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        if (quotation.getStatus() != QuotationStatus.PENDING) {
            throw new InvalidQuotationStatusException(quotation.getStatus(), "reject");
        }

        quotation.setStatus(QuotationStatus.REJECTED);
        quotation.setRespondedAt(LocalDateTime.now());

        Quotation updated = quotationRepository.save(quotation);
        
        // Send notification to transport about rejection
        sendQuotationRejectedNotification(updated);
        
        return mapToResponse(updated);
    }

    @Transactional
    public void expireQuotations() {
        List<Quotation> expiredQuotations = quotationRepository.findExpiredQuotations(LocalDateTime.now());

        for (Quotation quotation : expiredQuotations) {
            quotation.setStatus(QuotationStatus.EXPIRED);
            quotation.setRespondedAt(LocalDateTime.now());
        }

        quotationRepository.saveAll(expiredQuotations);
    }

    private Contract createContractFromQuotation(Quotation quotation, Booking booking) {
        Contract contract = new Contract();
        contract.setQuotationId(quotation.getQuotationId());
        contract.setBookingId(quotation.getBookingId());

        // Generate contract number
        String contractNumber = generateContractNumber();
        contract.setContractNumber(contractNumber);

        // Set default terms and conditions
        contract.setTermsAndConditions(getDefaultTermsAndConditions());

        contract.setTotalAmount(quotation.getQuotedPrice());
        contract.setAgreedPriceVnd(quotation.getQuotedPrice().longValue());

        // Calculate deposit (50% of total)
        long depositAmount = (long) (quotation.getQuotedPrice().longValue() * BookingConstants.DEFAULT_CONTRACT_DEPOSIT_PERCENTAGE);
        contract.setDepositRequiredVnd(depositAmount);

        // Set payment deadlines
        LocalDateTime now = LocalDateTime.now();
        contract.setDepositDueAt(now.plusDays(BookingConstants.DEPOSIT_DUE_DAYS));
        contract.setBalanceDueAt(now.plusDays(BookingConstants.BALANCE_DUE_DAYS));

        contract.setStatus(ContractStatus.DRAFT);

        return contractRepository.save(contract);
    }

    private String generateContractNumber() {
        String datePrefix = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseNumber = BookingConstants.CONTRACT_NUMBER_PREFIX + datePrefix + "-";

        long count = contractRepository.count();
        int sequence = (int) (count + 1);

        return baseNumber + String.format(BookingConstants.CONTRACT_SEQUENCE_FORMAT, sequence);
    }

    private String getDefaultTermsAndConditions() {
        return """
            HỢP ĐỒNG VẬN CHUYỂN HÀNG HÓA

            1. CÁC BÊN THAM GIA:
               - Bên A (Khách hàng): ...
               - Bên B (Đơn vị vận chuyển): ...

            2. ĐỐI TƯỢNG HỢP ĐỒNG:
               Dịch vụ vận chuyển hàng hóa theo yêu cầu.

            3. QUYỀN VÀ NGHĨA VỤ CÁC BÊN:
               ...

            4. ĐIỀU KHOẢN THANH TOÁN:
               - Tổng giá trị hợp đồng: ...
               - Đặt cọc: 50% tổng giá trị
               - Thanh toán còn lại: ...

            5. ĐIỀU KHOẢN PHẠT VI Phạm:
               ...

            6. ĐIỀU KHOẢN CUỐI CÙNG:
               ...
            """;
    }

    private void populateContractInfo(AcceptQuotationResponse response, Contract contract) {
        response.setContractId(contract.getContractId());
        response.setContractNumber(contract.getContractNumber());
        // Contract PDF generation not implemented yet => keep URL null.
    }

    private QuotationResponse mapToResponse(Quotation quotation) {
        QuotationResponse response = new QuotationResponse();
        response.setQuotationId(quotation.getQuotationId());
        response.setBookingId(quotation.getBookingId());
        response.setTransportId(quotation.getTransportId());
        
        // Populate vehicle info
        response.setVehicleId(quotation.getVehicleId());
        if (quotation.getVehicleId() != null) {
            vehicleRepository.findById(quotation.getVehicleId()).ifPresent(vehicle -> {
                response.setVehicleModel(vehicle.getModel());
                response.setVehicleLicensePlate(vehicle.getLicensePlate());
                response.setVehicleCapacityKg(vehicle.getCapacityKg());
                response.setVehicleCapacityM3(vehicle.getCapacityM3());
            });
        }
        
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
    }

    private QuotationDetailResponse mapToDetailResponse(Quotation quotation) {
        QuotationDetailResponse response = new QuotationDetailResponse();
        response.setQuotationId(quotation.getQuotationId());
        response.setBookingId(quotation.getBookingId());
        response.setTransportId(quotation.getTransportId());

        // Pricing breakdown
        response.setBasePrice(quotation.getBasePrice());
        response.setDistancePrice(quotation.getDistancePrice());
        response.setItemHandlingPrice(quotation.getItemsPrice());
        response.setAdditionalServicesPrice(quotation.getAdditionalFees());
        response.setTotalPrice(quotation.getQuotedPrice());

        // Parse services from price breakdown JSON (if available)
        parseServicesFromPriceBreakdown(response, quotation.getPriceBreakdown());

        // Load transport information
        Transport transport = transportRepository.findById(quotation.getTransportId()).orElse(null);
        if (transport != null) {
            response.setTransporterName(transport.getCompanyName());
            // Note: avatar URL not stored in transport table
            response.setTransporterAvatar(null);
            response.setTransporterRating(transport.getAverageRating() != null ? transport.getAverageRating().doubleValue() : 0.0);
            response.setTransporterCompletedJobs(transport.getCompletedBookings());
        }

        // Status and timing
        response.setStatus(quotation.getStatus().name());
        response.setIsSelected(quotation.getStatus() == QuotationStatus.ACCEPTED);
        response.setExpiresAt(quotation.getExpiresAt());
        response.setCreatedAt(quotation.getCreatedAt());
        response.setUpdatedAt(quotation.getCreatedAt()); // No updated_at field
        response.setAcceptedAt(quotation.getAcceptedAt());
        if (quotation.getStatus() == QuotationStatus.REJECTED) {
            response.setRejectedAt(quotation.getRespondedAt());
        }

        response.setNotes(quotation.getNotes());
        response.setMetadata(quotation.getPriceBreakdown());

        return response;
    }

    private void parseServicesFromPriceBreakdown(QuotationDetailResponse response, String priceBreakdown) {
        if (priceBreakdown == null || priceBreakdown.trim().isEmpty()) {
            return;
        }

        try {
            // Simple JSON parsing for the services
            if (priceBreakdown.contains("\"includesPackaging\":")) {
                response.setIncludesPackaging(priceBreakdown.contains("\"includesPackaging\":true"));
            }
            if (priceBreakdown.contains("\"includesDisassembly\":")) {
                response.setIncludesDisassembly(priceBreakdown.contains("\"includesDisassembly\":true"));
            }
            if (priceBreakdown.contains("\"includesInsurance\":")) {
                response.setIncludesInsurance(priceBreakdown.contains("\"includesInsurance\":true"));
            }
            if (priceBreakdown.contains("\"estimatedDurationHours\":")) {
                // Extract estimated duration - simplified parsing
                int start = priceBreakdown.indexOf("\"estimatedDurationHours\":") + 25;
                int end = priceBreakdown.indexOf(",", start);
                if (end == -1) end = priceBreakdown.indexOf("}", start);
                if (start > 25 && end > start) {
                    String durationStr = priceBreakdown.substring(start, end);
                    try {
                        response.setEstimatedDurationHours(new BigDecimal(durationStr.trim()));
                    } catch (NumberFormatException e) {
                        // Ignore parsing errors
                    }
                }
            }
        } catch (Exception e) {
            // If parsing fails, leave defaults
        }
    }
}
