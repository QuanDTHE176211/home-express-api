package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.BaseIntegrationTest;
import com.homeexpress.home_express_api.dto.booking.BookingRequest;
import com.homeexpress.home_express_api.dto.booking.BookingResponse;
import com.homeexpress.home_express_api.dto.booking.AddressDto;
import com.homeexpress.home_express_api.dto.request.InitiateDepositRequest;
import com.homeexpress.home_express_api.dto.request.PaymentMethodRequest;
import com.homeexpress.home_express_api.dto.response.InitiateDepositResponse;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class PaymentIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransportRepository transportRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private VnProvinceRepository provinceRepository;

    @Autowired
    private VnDistrictRepository districtRepository;

    @Autowired
    private VnWardRepository wardRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    private User customerUser;
    private User transportUser;
    private Transport transport;
    private Long categoryId;
    private Booking booking;

    @BeforeEach
    void setUp() {
        // 1. Seed Locations
        Province province = new Province();
        province.setCode("79");
        province.setName("Ho Chi Minh");
        province.setRegion("SOUTH");
        provinceRepository.save(province);

        District district = new District();
        district.setCode("760");
        district.setName("Quan 1");
        district.setProvinceCode("79");
        districtRepository.save(district);

        Ward ward = new Ward();
        ward.setCode("26734");
        ward.setName("Ben Nghe");
        ward.setDistrictCode("760");
        wardRepository.save(ward);

        // 2. Seed Category
        Category category = new Category();
        category.setName("Furniture");
        category = categoryRepository.save(category);
        categoryId = category.getCategoryId();

        // 3. Seed Customer
        customerUser = new User();
        customerUser.setEmail("customer@test.com");
        customerUser.setPasswordHash("hashed_password");
        customerUser.setRole(UserRole.CUSTOMER);
        customerUser = userRepository.save(customerUser);

        Customer customer = new Customer();
        customer.setCustomerId(customerUser.getUserId());
        customer.setUser(customerUser);
        customer.setFullName("Test Customer");
        customer.setPhone("0901234567");
        customerRepository.save(customer);

        // 4. Seed Transport
        transportUser = new User();
        transportUser.setEmail("transport@test.com");
        transportUser.setPasswordHash("hashed_password");
        transportUser.setRole(UserRole.TRANSPORT);
        transportUser = userRepository.save(transportUser);

        transport = new Transport();
        transport.setTransportId(transportUser.getUserId());
        transport.setUser(transportUser);
        transport.setCompanyName("Test Transport");
        transport.setPhone("0909999999");
        transport.setAddress("123 Transport St");
        transport.setCity("Ho Chi Minh");
        transport.setBusinessLicenseNumber("0123456789");
        transportRepository.save(transport);

        // 5. Create a Booking with Final Price (Simulating Quoted & Accepted)
        BookingRequest request = new BookingRequest();
        request.setPreferredDate(LocalDate.now().plusDays(2));
        //request.setPreferredTimeSlot(TimeSlot.MORNING);
        request.setNotes("Payment Test Booking");

        AddressDto pickup = new AddressDto();
        pickup.setAddressLine("123 Test St");
        pickup.setProvinceCode("79");
        pickup.setDistrictCode("760");
        pickup.setWardCode("26734");
        pickup.setLat(BigDecimal.valueOf(10.1));
        pickup.setLng(BigDecimal.valueOf(106.1));
        request.setPickupAddress(pickup);

        AddressDto delivery = new AddressDto();
        delivery.setAddressLine("456 Dest St");
        delivery.setProvinceCode("79");
        delivery.setDistrictCode("760");
        delivery.setWardCode("26734");
        delivery.setLat(BigDecimal.valueOf(10.2));
        delivery.setLng(BigDecimal.valueOf(106.2));
        request.setDeliveryAddress(delivery);

        request.setItems(new ArrayList<>());
        BookingRequest.ItemDto item = new BookingRequest.ItemDto();
        item.setName("Sofa");
        item.setQuantity(1);
        item.setCategoryId(categoryId);
        request.getItems().add(item);

        BookingResponse response = bookingService.createBooking(request, customerUser.getUserId(), UserRole.CUSTOMER);
        
        // Manually set transport and price to simulate accepted quote
        booking = bookingRepository.findById(response.getBookingId()).orElseThrow();
        booking.setTransportId(transport.getTransportId());
        booking.setFinalPrice(BigDecimal.valueOf(1000000)); // 1 million VND
        booking.setStatus(BookingStatus.QUOTED); // Assuming ready for deposit
        bookingRepository.save(booking);
    }

    @Test
    @WithMockUser(username = "customer@test.com", roles = "CUSTOMER")
    void initiateDepositPayment_Cash_ShouldConfirmImmediately() {
        // Arrange
        InitiateDepositRequest request = new InitiateDepositRequest();
        request.setBookingId(booking.getBookingId());
        request.setMethod(PaymentMethodRequest.CASH);

        // Act
        InitiateDepositResponse response = paymentService.initiateDepositPayment(request, customerUser.getUserId());

        // Assert
        assertTrue(response.isSuccess());
        assertEquals(300000.0, response.getDepositAmount()); // 30% of 1M = 300k

        // Verify Payment Record
        List<Payment> payments = paymentRepository.findByBookingId(booking.getBookingId());
        assertEquals(1, payments.size());
        Payment payment = payments.get(0);
        assertEquals(PaymentType.DEPOSIT, payment.getPaymentType());
        assertEquals(PaymentMethod.CASH, payment.getPaymentMethod());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus()); // Cash auto-completes
        
        // Verify Booking Status Update
        Booking updatedBooking = bookingRepository.findById(booking.getBookingId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, updatedBooking.getStatus());
    }

    @Test
    @WithMockUser(username = "customer@test.com", roles = "CUSTOMER")
    void initiateDepositPayment_BankTransfer_ShouldStayPending() {
        // Arrange
        InitiateDepositRequest request = new InitiateDepositRequest();
        request.setBookingId(booking.getBookingId());
        request.setMethod(PaymentMethodRequest.BANK);

        // Act
        InitiateDepositResponse response = paymentService.initiateDepositPayment(request, customerUser.getUserId());

        // Assert
        assertTrue(response.isSuccess());

        // Verify Payment Record
        List<Payment> payments = paymentRepository.findByBookingId(booking.getBookingId());
        assertEquals(1, payments.size());
        Payment payment = payments.get(0);
        assertEquals(PaymentType.DEPOSIT, payment.getPaymentType());
        assertEquals(PaymentMethod.BANK_TRANSFER, payment.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, payment.getStatus()); // Bank transfer waits for confirmation
        
        // Verify Booking Status (Should still be confirmed in current logic because initiation triggers confirmed)
        // Note: Check logic in PaymentService.initiateDepositPayment -> updateBookingStatusAfterInitiation
        Booking updatedBooking = bookingRepository.findById(booking.getBookingId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, updatedBooking.getStatus());
    }
}
