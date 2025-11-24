package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.BaseIntegrationTest;
import com.homeexpress.home_express_api.dto.booking.AddressDto;
import com.homeexpress.home_express_api.dto.booking.BookingRequest;
import com.homeexpress.home_express_api.dto.booking.BookingResponse;
import com.homeexpress.home_express_api.dto.booking.BookingUpdateRequest;
import com.homeexpress.home_express_api.dto.request.ConfirmCompletionRequest;
import com.homeexpress.home_express_api.dto.request.InitiateDepositRequest;
import com.homeexpress.home_express_api.dto.request.InitiateRemainingPaymentRequest;
import com.homeexpress.home_express_api.dto.request.PaymentMethodRequest;
import com.homeexpress.home_express_api.entity.ActorRole;
import com.homeexpress.home_express_api.entity.Booking;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.entity.BookingStatusHistory;
import com.homeexpress.home_express_api.entity.Customer;
import com.homeexpress.home_express_api.entity.TimeSlot;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.BookingStatusHistoryRepository;
import com.homeexpress.home_express_api.repository.CustomerRepository;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Transactional
class BookingStatusFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransportJobService transportJobService;

    @Autowired
    private BookingStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransportRepository transportRepository;

    private User customerUser;
    private Transport transport;

    @BeforeEach
    void setUp() {
        customerUser = new User();
        customerUser.setEmail("booking.flow+customer@test.com");
        customerUser.setPasswordHash("hashed_password");
        customerUser.setRole(UserRole.CUSTOMER);
        customerUser = userRepository.save(customerUser);

        Customer customer = new Customer();
        customer.setCustomerId(customerUser.getUserId());
        customer.setUser(customerUser);
        customer.setFullName("Flow Customer");
        customer.setPhone("0901111222");
        customerRepository.save(customer);

        User transportUser = new User();
        transportUser.setEmail("booking.flow+transport@test.com");
        transportUser.setPasswordHash("hashed_password");
        transportUser.setRole(UserRole.TRANSPORT);
        transportUser = userRepository.save(transportUser);

        transport = new Transport();
        transport.setTransportId(transportUser.getUserId());
        transport.setUser(transportUser);
        transport.setCompanyName("Flow Transport");
        transport.setBusinessLicenseNumber("0123456789");
        transport.setPhone("0903333444");
        transport.setAddress("99 Test St");
        transport.setCity("Ho Chi Minh");
        transport.setVerificationStatus(VerificationStatus.APPROVED);
        transport.setReadyToQuote(true);
        transportRepository.save(transport);
    }

    @Test
    @WithMockUser(username = "booking.flow+customer@test.com", roles = "CUSTOMER")
    void bookingLifecycle_shouldFollowHappyPathAndRecordHistory() {
        BookingResponse created = bookingService.createBooking(
                buildBookingRequest(),
                customerUser.getUserId(),
                UserRole.CUSTOMER
        );

        bookingService.assignTransport(
                created.getBookingId(),
                transport.getTransportId(),
                BigDecimal.valueOf(1_200_000),
                customerUser.getUserId(),
                UserRole.CUSTOMER
        );

        Booking quotedBooking = bookingRepository.findById(created.getBookingId()).orElseThrow();
        quotedBooking.setFinalPrice(BigDecimal.valueOf(1_200_000));
        bookingRepository.save(quotedBooking);

        InitiateDepositRequest depositRequest = new InitiateDepositRequest(
                created.getBookingId(),
                PaymentMethodRequest.CASH,
                null,
                null
        );
        paymentService.initiateDepositPayment(depositRequest, customerUser.getUserId());

        Booking confirmedBooking = bookingRepository.findById(created.getBookingId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, confirmedBooking.getStatus());

        transportJobService.startJob(created.getBookingId(), transport.getTransportId());
        transportJobService.completeJob(created.getBookingId(), transport.getTransportId(), "Completed", List.of());

        InitiateRemainingPaymentRequest remainingRequest = new InitiateRemainingPaymentRequest(
                created.getBookingId(),
                PaymentMethodRequest.CASH,
                0L,
                null,
                null
        );
        paymentService.initiateRemainingPayment(remainingRequest, customerUser.getUserId());

        BookingResponse closed = bookingService.confirmBookingCompletion(
                created.getBookingId(),
                new ConfirmCompletionRequest("Great work", 5),
                customerUser.getUserId()
        );

        assertEquals(BookingStatus.CONFIRMED_BY_CUSTOMER, closed.getStatus());

        List<BookingStatusHistory> history = statusHistoryRepository.findByBookingIdOrderByChangedAtAsc(created.getBookingId());
        assertEquals(
                List.of(
                        BookingStatus.PENDING,
                        BookingStatus.QUOTED,
                        BookingStatus.CONFIRMED,
                        BookingStatus.IN_PROGRESS,
                        BookingStatus.COMPLETED,
                        BookingStatus.CONFIRMED_BY_CUSTOMER
                ),
                history.stream().map(BookingStatusHistory::getNewStatus).toList()
        );

        assertEquals(ActorRole.CUSTOMER, history.get(0).getChangedByRole());
        assertEquals(ActorRole.CUSTOMER, history.get(1).getChangedByRole());
        assertEquals(ActorRole.CUSTOMER, history.get(2).getChangedByRole());
        assertEquals(ActorRole.TRANSPORT, history.get(3).getChangedByRole());
        assertEquals(ActorRole.TRANSPORT, history.get(4).getChangedByRole());
        assertEquals(ActorRole.CUSTOMER, history.get(5).getChangedByRole());
    }

    @Test
    @WithMockUser(username = "booking.flow+customer@test.com", roles = "CUSTOMER")
    void updateBooking_shouldRejectInvalidJump() {
        BookingResponse created = bookingService.createBooking(
                buildBookingRequest(),
                customerUser.getUserId(),
                UserRole.CUSTOMER
        );

        BookingUpdateRequest updateRequest = new BookingUpdateRequest();
        updateRequest.setStatus(BookingStatus.COMPLETED);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.updateBooking(
                        created.getBookingId(),
                        updateRequest,
                        customerUser.getUserId(),
                        UserRole.CUSTOMER
                )
        );

        assertTrue(ex.getMessage().contains("Invalid status transition"));
    }

    private BookingRequest buildBookingRequest() {
        BookingRequest request = new BookingRequest();
        request.setPreferredDate(LocalDate.now().plusDays(1));
        request.setPreferredTimeSlot(TimeSlot.MORNING);
        request.setNotes("State flow test");

        AddressDto pickup = new AddressDto();
        pickup.setAddressLine("123 Pickup St");
        request.setPickupAddress(pickup);

        AddressDto delivery = new AddressDto();
        delivery.setAddressLine("456 Delivery St");
        request.setDeliveryAddress(delivery);

        return request;
    }
}
