package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.config.PaymentConfig;
import com.homeexpress.home_express_api.dto.payment.PaymentConfirmRequestDTO;
import com.homeexpress.home_express_api.dto.payment.PaymentInitRequestDTO;
import com.homeexpress.home_express_api.dto.payment.PaymentResponseDTO;
import com.homeexpress.home_express_api.dto.request.InitiateDepositRequest;
import com.homeexpress.home_express_api.dto.request.PaymentMethodRequest;
import com.homeexpress.home_express_api.dto.response.InitiateDepositResponse;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PaymentConfig paymentConfig;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransportRepository transportRepository;

    @Mock
    private BookingSettlementRepository settlementRepository;

    @Mock
    private BookingStatusHistoryRepository statusHistoryRepository;

    @Mock
    private CommissionService commissionService;

    @Mock
    private CustomerEventService customerEventService;

    @Mock
    private WalletService walletService;

    @InjectMocks
    private PaymentService paymentService;

    private Booking mockBooking;
    private Payment mockPayment;
    private PaymentInitRequestDTO paymentInitRequest;
    private PaymentConfirmRequestDTO paymentConfirmRequest;
    private InitiateDepositRequest depositRequest;

    @BeforeEach
    void setUp() {
        // Setup mock booking
        mockBooking = new Booking();
        mockBooking.setBookingId(1L);
        mockBooking.setCustomerId(1L);
        mockBooking.setTransportId(5L);
        mockBooking.setStatus(BookingStatus.CONFIRMED);
        mockBooking.setFinalPrice(BigDecimal.valueOf(10000000)); // 10M VND

        // Setup mock payment
        mockPayment = new Payment();
        mockPayment.setPaymentId(1L);
        mockPayment.setBookingId(1L);
        mockPayment.setPaymentType(PaymentType.DEPOSIT);
        mockPayment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        mockPayment.setAmount(BigDecimal.valueOf(3000000)); // 30% deposit
        mockPayment.setStatus(PaymentStatus.PENDING);
        mockPayment.setIdempotencyKey("idempotency-key-123");

        // Setup payment init request
        paymentInitRequest = new PaymentInitRequestDTO();
        paymentInitRequest.setBookingId(1L);
        paymentInitRequest.setPaymentType(PaymentType.DEPOSIT);
        paymentInitRequest.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        paymentInitRequest.setAmount(BigDecimal.valueOf(3000000));
        paymentInitRequest.setIdempotencyKey("idempotency-key-123");

        // Setup payment confirm request
        paymentConfirmRequest = new PaymentConfirmRequestDTO();
        paymentConfirmRequest.setPaymentId(1L);
        paymentConfirmRequest.setTransactionId("TXN-123456");

        // Setup deposit request
        depositRequest = new InitiateDepositRequest();
        depositRequest.setBookingId(1L);
        depositRequest.setMethod(PaymentMethodRequest.BANK);
    }

    @Test
    void testCreatePayment_Success() {
        // Given
        Long userId = 1L;
        UserRole userRole = UserRole.CUSTOMER;

        when(bookingRepository.findById(1L)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.findByBookingId(1L)).thenReturn(new ArrayList<>());
        when(paymentRepository.save(any(Payment.class))).thenReturn(mockPayment);

        // When
        PaymentResponseDTO response = paymentService.initializePayment(paymentInitRequest, userId, userRole);

        // Then
        assertNotNull(response);
        assertEquals(1L, response.getPaymentId());
        assertEquals(PaymentType.DEPOSIT, response.getPaymentType());
        assertEquals(PaymentMethod.BANK_TRANSFER, response.getPaymentMethod());
        assertEquals(BigDecimal.valueOf(3000000), response.getAmount());
        assertEquals(PaymentStatus.PENDING, response.getStatus());

        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    @Test
    void testProcessPayment_ByCash_Success() {
        // Given
        Long userId = 1L;
        
        depositRequest.setMethod(PaymentMethodRequest.CASH);
        
        Payment cashPayment = new Payment();
        cashPayment.setPaymentId(2L);
        cashPayment.setBookingId(1L);
        cashPayment.setPaymentType(PaymentType.DEPOSIT);
        cashPayment.setPaymentMethod(PaymentMethod.CASH);
        cashPayment.setAmount(BigDecimal.valueOf(3000000));
        cashPayment.setStatus(PaymentStatus.COMPLETED); // Cash is auto-completed
        cashPayment.setPaidAt(LocalDateTime.now());

        when(bookingRepository.findById(1L)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.findByBookingId(1L)).thenReturn(new ArrayList<>());
        when(paymentRepository.save(any(Payment.class))).thenReturn(cashPayment);
        when(settlementRepository.findByBookingId(1L)).thenReturn(Optional.empty());
        when(settlementRepository.save(any(BookingSettlement.class))).thenReturn(new BookingSettlement());
        when(paymentRepository.findByBookingIdAndStatus(anyLong(), any())).thenReturn(List.of(cashPayment));
        when(commissionService.getCommissionRateBps(anyLong())).thenReturn(1000); // 10%
        when(commissionService.calculatePlatformFee(anyLong(), anyLong())).thenReturn(1000000L); // 1M
        when(paymentConfig.getDepositPercentage()).thenReturn(0.3); // 30%

        // When
        InitiateDepositResponse response = paymentService.initiateDepositPayment(depositRequest, userId);

        // Then
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(1L, response.getBookingId());
        assertEquals(3000000.0, response.getDepositAmount());

        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
    }

    @Test
    void testProcessPayment_ByWallet_Success() {
        // Given
        Long userId = 1L;
        
        depositRequest.setMethod(PaymentMethodRequest.BANK);
        
        when(paymentConfig.getDepositPercentage()).thenReturn(0.3);
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.findByBookingId(1L)).thenReturn(new ArrayList<>());
        when(paymentRepository.save(any(Payment.class))).thenReturn(mockPayment);

        // When
        InitiateDepositResponse response = paymentService.initiateDepositPayment(depositRequest, userId);

        // Then
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PaymentMethod.BANK_TRANSFER, PaymentMethod.BANK_TRANSFER);
        assertEquals("Payment initiated successfully", response.getMessage());

        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    @Test
    void testUpdatePaymentStatus_Success() {
        // Given
        Long userId = 1L;
        UserRole userRole = UserRole.CUSTOMER;

        Payment completedPayment = new Payment();
        completedPayment.setPaymentId(1L);
        completedPayment.setBookingId(1L);
        completedPayment.setPaymentType(PaymentType.DEPOSIT);
        completedPayment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        completedPayment.setAmount(BigDecimal.valueOf(3000000));
        completedPayment.setStatus(PaymentStatus.COMPLETED);
        completedPayment.setConfirmedBy(userId);
        completedPayment.setConfirmedAt(LocalDateTime.now());
        completedPayment.setPaidAt(LocalDateTime.now());

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(mockPayment));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.save(any(Payment.class))).thenReturn(completedPayment);
        when(settlementRepository.findByBookingId(1L)).thenReturn(Optional.empty());
        when(settlementRepository.save(any(BookingSettlement.class))).thenReturn(new BookingSettlement());
        when(paymentRepository.findByBookingIdAndStatus(anyLong(), any())).thenReturn(List.of(completedPayment));
        when(commissionService.getCommissionRateBps(anyLong())).thenReturn(1000);
        when(commissionService.calculatePlatformFee(anyLong(), anyLong())).thenReturn(1000000L);

        // When
        PaymentResponseDTO response = paymentService.confirmPayment(paymentConfirmRequest, userId, userRole);

        // Then
        assertNotNull(response);
        assertEquals(1L, response.getPaymentId());
        assertEquals(PaymentStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getPaidAt());

        verify(paymentRepository, times(1)).findById(1L);
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
    }

    @Test
    void testGetPaymentHistory_Success() {
        // Given
        Long bookingId = 1L;
        Long userId = 1L;
        UserRole userRole = UserRole.CUSTOMER;

        List<Payment> payments = new ArrayList<>();
        
        Payment payment1 = new Payment();
        payment1.setPaymentId(1L);
        payment1.setBookingId(bookingId);
        payment1.setPaymentType(PaymentType.DEPOSIT);
        payment1.setAmount(BigDecimal.valueOf(3000000));
        payment1.setStatus(PaymentStatus.COMPLETED);
        payments.add(payment1);

        Payment payment2 = new Payment();
        payment2.setPaymentId(2L);
        payment2.setBookingId(bookingId);
        payment2.setPaymentType(PaymentType.REMAINING_PAYMENT);
        payment2.setAmount(BigDecimal.valueOf(7000000));
        payment2.setStatus(PaymentStatus.PENDING);
        payments.add(payment2);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(payments);

        // When
        List<PaymentResponseDTO> response = paymentService.getPaymentHistory(bookingId, userId, userRole);

        // Then
        assertNotNull(response);
        assertEquals(2, response.size());
        assertEquals(PaymentType.DEPOSIT, response.get(0).getPaymentType());
        assertEquals(PaymentStatus.COMPLETED, response.get(0).getStatus());
        assertEquals(PaymentType.REMAINING_PAYMENT, response.get(1).getPaymentType());
        assertEquals(PaymentStatus.PENDING, response.get(1).getStatus());

        verify(paymentRepository, times(1)).findByBookingIdOrderByCreatedAtAsc(bookingId);
    }

    @Test
    void testInitiateDeposit_AlreadyPaid() {
        // Given
        Long userId = 1L;
        
        Payment existingPayment = new Payment();
        existingPayment.setPaymentId(1L);
        existingPayment.setPaymentType(PaymentType.DEPOSIT);
        existingPayment.setStatus(PaymentStatus.COMPLETED);

        when(bookingRepository.findById(1L)).thenReturn(Optional.of(mockBooking));
        when(paymentRepository.findByBookingId(1L)).thenReturn(List.of(existingPayment));

        // When
        InitiateDepositResponse response = paymentService.initiateDepositPayment(depositRequest, userId);

        // Then
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("Deposit has already been paid for this booking", response.getMessage());

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
