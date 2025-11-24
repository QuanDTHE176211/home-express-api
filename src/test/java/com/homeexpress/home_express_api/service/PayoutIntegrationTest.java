package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.BaseIntegrationTest;
import com.homeexpress.home_express_api.dto.payout.PayoutDTO;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class PayoutIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransportRepository transportRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingSettlementRepository settlementRepository;

    @Autowired
    private TransportWalletRepository walletRepository;

    @Autowired
    private TransportPayoutRepository payoutRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private User transportUser;
    private Transport transport;
    private User customerUser;
    private Customer customer;
    private Booking booking;
    private TransportWallet wallet;

    @BeforeEach
    void setUp() {
        // 1. Seed Transport & User
        transportUser = new User();
        transportUser.setEmail("transport_payout@test.com");
        transportUser.setPasswordHash("hashed_password");
        transportUser.setRole(UserRole.TRANSPORT);
        transportUser = userRepository.save(transportUser);

        transport = new Transport();
        transport.setTransportId(transportUser.getUserId());
        transport.setUser(transportUser);
        transport.setCompanyName("Payout Transport Co");
        transport.setPhone("0908888888");
        transport.setAddress("Payout St");
        transport.setCity("HCM");
        transport.setBusinessLicenseNumber("0123456789");
        transport.setBankCode("VCB");
        transport.setBankAccountNumber("1234567890");
        transport.setBankAccountHolder("PAYOUT COMPANY");
        transportRepository.save(transport);

        // 2. Seed Customer
        customerUser = new User();
        customerUser.setEmail("customer_payout@test.com");
        customerUser.setPasswordHash("hashed_password");
        customerUser.setRole(UserRole.CUSTOMER);
        customerUser = userRepository.save(customerUser);

        customer = new Customer();
        customer.setCustomerId(customerUser.getUserId());
        customer.setUser(customerUser);
        customer.setFullName("Payout Customer");
        customer.setPhone("0907777777");
        customerRepository.save(customer);

        // 3. Seed Wallet with initial balance (to cover payout)
        wallet = new TransportWallet();
        wallet.setTransportId(transport.getTransportId());
        wallet.setCurrentBalanceVnd(5000000L); // 5M VND
        wallet.setTotalEarnedVnd(5000000L);
        walletRepository.save(wallet);

        // 4. Seed Booking
        booking = new Booking();
        booking.setCustomerId(customer.getCustomerId());
        booking.setTransportId(transport.getTransportId());
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setPickupAddress("A");
        booking.setDeliveryAddress("B");
        booking.setPreferredDate(LocalDate.now());
        booking.setFinalPrice(BigDecimal.valueOf(1000000));
        bookingRepository.save(booking);
    }

    @Test
    void createPayoutBatch_Success() {
        // Arrange: Create a READY settlement
        BookingSettlement settlement = new BookingSettlement();
        settlement.setBookingId(booking.getBookingId());
        settlement.setTransportId(transport.getTransportId());
        settlement.setAgreedPriceVnd(1000000L);
        settlement.setTotalCollectedVnd(1000000L); // All online payment
        settlement.setPlatformFeeVnd(100000L); // 10% fee
        settlement.setStatus(SettlementStatus.READY);
        // net = 1000000 - 100000 = 900000
        settlementRepository.save(settlement);

        // Act
        PayoutDTO result = payoutService.createPayoutBatch(transport.getTransportId());

        // Assert
        assertNotNull(result);
        assertEquals(PayoutStatus.PENDING, result.getStatus());
        assertEquals(900000L, result.getTotalAmountVnd());
        assertEquals(1, result.getItemCount());
        
        // Verify DB state
        TransportPayout savedPayout = payoutRepository.findById(result.getPayoutId()).orElseThrow();
        assertEquals(900000L, savedPayout.getTotalAmountVnd());
        
        // Verify settlement updated
        BookingSettlement updatedSettlement = settlementRepository.findById(settlement.getSettlementId()).orElseThrow();
        assertEquals(SettlementStatus.IN_PAYOUT, updatedSettlement.getStatus());
        assertEquals(result.getPayoutId(), updatedSettlement.getPayoutId());
    }

    @Test
    void createPayoutBatch_InsufficientBalance_ShouldFail() {
        // Arrange: Set wallet balance to 0
        wallet.setCurrentBalanceVnd(0L);
        walletRepository.save(wallet);

        // Arrange: Create a READY settlement
        BookingSettlement settlement = new BookingSettlement();
        settlement.setBookingId(booking.getBookingId());
        settlement.setTransportId(transport.getTransportId());
        settlement.setAgreedPriceVnd(1000000L);
        settlement.setTotalCollectedVnd(1000000L);
        settlement.setPlatformFeeVnd(100000L);
        settlement.setStatus(SettlementStatus.READY);
        settlementRepository.save(settlement);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            payoutService.createPayoutBatch(transport.getTransportId());
        });
    }

    @Test
    void updatePayoutStatus_ToCompleted_ShouldDebitWallet() {
        // Arrange: Create a PENDING payout
        TransportPayout payout = new TransportPayout();
        payout.setTransportId(transport.getTransportId());
        payout.setTotalAmountVnd(900000L);
        payout.setItemCount(1);
        payout.setStatus(PayoutStatus.PENDING);
        payout.setPayoutNumber("TEST-PO-001");
        payoutRepository.save(payout);

        long initialBalance = wallet.getCurrentBalanceVnd(); // 5,000,000

        // Act
        PayoutDTO result = payoutService.updatePayoutStatus(payout.getPayoutId(), PayoutStatus.COMPLETED, null, "TXN123");

        // Assert
        assertEquals(PayoutStatus.COMPLETED, result.getStatus());
        
        // Verify Wallet Debited
        TransportWallet updatedWallet = walletRepository.findByTransportId(transport.getTransportId()).orElseThrow();
        assertEquals(initialBalance - 900000L, updatedWallet.getCurrentBalanceVnd());
        assertEquals(4100000L, updatedWallet.getCurrentBalanceVnd());
    }
}
