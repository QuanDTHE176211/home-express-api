package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.booking.*;
import com.homeexpress.home_express_api.entity.*;
import com.homeexpress.home_express_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingStatusHistoryRepository statusHistoryRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private VnProvinceRepository provinceRepository;

    @Mock
    private VnDistrictRepository districtRepository;

    @Mock
    private VnWardRepository wardRepository;

    @Mock
    private BookingItemRepository bookingItemRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransportRepository transportRepository;

    @Mock
    private CustomerEventService customerEventService;

    @InjectMocks
    private BookingService bookingService;

    private BookingRequest bookingRequest;
    private Booking mockBooking;
    private Customer mockCustomer;
    private User mockUser;

    @BeforeEach
    void setUp() {
        // Setup booking request
        bookingRequest = new BookingRequest();
        bookingRequest.setPreferredDate(LocalDate.now().plusDays(1));
        bookingRequest.setPreferredTimeSlot(TimeSlot.MORNING);
        bookingRequest.setNotes("Test booking");
        
        // Setup pickup address
        AddressDto pickupAddress = new AddressDto();
        pickupAddress.setAddressLine("123 Nguyen Hue");
        pickupAddress.setProvinceCode("79");
        pickupAddress.setDistrictCode("760");
        pickupAddress.setWardCode("26734");
        pickupAddress.setLat(BigDecimal.valueOf(10.7769));
        pickupAddress.setLng(BigDecimal.valueOf(106.7009));
        bookingRequest.setPickupAddress(pickupAddress);

        // Setup delivery address
        AddressDto deliveryAddress = new AddressDto();
        deliveryAddress.setAddressLine("456 Le Loi");
        deliveryAddress.setProvinceCode("79");
        deliveryAddress.setDistrictCode("769");
        deliveryAddress.setWardCode("27259");
        deliveryAddress.setLat(BigDecimal.valueOf(10.8231));
        deliveryAddress.setLng(BigDecimal.valueOf(106.6297));
        bookingRequest.setDeliveryAddress(deliveryAddress);

        // Setup items
        bookingRequest.setItems(new ArrayList<>());
        BookingRequest.ItemDto item = new BookingRequest.ItemDto();
        item.setCategoryId(1L);
        item.setName("Sofa");
        item.setQuantity(1);
        item.setWeight(BigDecimal.valueOf(50));
        item.setDeclaredValueVnd(BigDecimal.valueOf(5000000));
        bookingRequest.getItems().add(item);

        // Setup mock booking
        mockBooking = new Booking();
        mockBooking.setBookingId(1L);
        mockBooking.setCustomerId(1L);
        mockBooking.setStatus(BookingStatus.PENDING);
        mockBooking.setPickupAddress("123 Nguyen Hue");
        mockBooking.setDeliveryAddress("456 Le Loi");
        mockBooking.setPreferredDate(LocalDate.now().plusDays(1));
        mockBooking.setCreatedAt(LocalDateTime.now());

        // Setup mock customer
        mockCustomer = new Customer();
        mockCustomer.setCustomerId(1L);
        mockCustomer.setFullName("John Doe");
        mockCustomer.setPhone("0901234567");

        // Setup mock user
        mockUser = new User();
        mockUser.setUserId(1L);
        mockUser.setEmail("customer@test.com");
        mockUser.setRole(UserRole.CUSTOMER);
    }

    @Test
    void testCreateBooking_Stage1_Draft() {
        // Given - Customer creates a new booking (PENDING = Draft)
        Long customerId = 1L;
        
        when(customerRepository.existsById(customerId)).thenReturn(true);
        when(provinceRepository.existsById(anyString())).thenReturn(true);
        when(districtRepository.existsById(anyString())).thenReturn(true);
        when(wardRepository.existsById(anyString())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(mockBooking);
        when(bookingItemRepository.save(any(BookingItem.class))).thenReturn(new BookingItem());
        when(statusHistoryRepository.save(any(BookingStatusHistory.class)))
                .thenReturn(new BookingStatusHistory());
        when(userRepository.findById(customerId)).thenReturn(Optional.of(mockUser));

        // When
        BookingResponse response = bookingService.createBooking(bookingRequest, customerId, UserRole.CUSTOMER);

        // Then
        assertNotNull(response);
        assertEquals(1L, response.getBookingId());
        assertEquals(BookingStatus.PENDING, response.getStatus());
        assertEquals(customerId, response.getCustomerId());
        
        verify(bookingRepository, times(1)).save(any(Booking.class));
        verify(bookingItemRepository, times(1)).save(any(BookingItem.class));
        verify(statusHistoryRepository, times(1)).save(argThat(history ->
            history.getNewStatus() == BookingStatus.PENDING &&
            history.getChangedByRole() == ActorRole.CUSTOMER
        ));
    }

    @Test
    void testUpdateBooking_Stage2_PendingIntake() {
        // Given - Customer submits booking for intake (still using PENDING status in current system)
        Long bookingId = 1L;
        Long customerId = 1L;
        
        BookingUpdateRequest updateRequest = new BookingUpdateRequest();
        updateRequest.setNotes("Ready for intake");
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(mockBooking);

        // When
        BookingResponse response = bookingService.updateBooking(
                bookingId, 
                updateRequest, 
                customerId, 
                UserRole.CUSTOMER
        );

        // Then
        assertNotNull(response);
        assertEquals("Ready for intake", response.getNotes());
        verify(bookingRepository, times(1)).save(any(Booking.class));
    }

    @Test
    void testUpdateBooking_Stage3_IntakeInProgress() {
        // Given - Manager triggers AI analysis (moves to QUOTED or custom status)
        Long bookingId = 1L;
        Long managerId = 10L;
        
        mockBooking.setStatus(BookingStatus.PENDING);
        
        User managerUser = new User();
        managerUser.setUserId(managerId);
        managerUser.setRole(UserRole.MANAGER);
        
        BookingUpdateRequest updateRequest = new BookingUpdateRequest();
        updateRequest.setStatus(BookingStatus.QUOTED); // Simulating intake complete
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setStatus(BookingStatus.QUOTED);
            return booking;
        });
        when(statusHistoryRepository.save(any(BookingStatusHistory.class)))
                .thenReturn(new BookingStatusHistory());
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(mockUser));

        // When
        BookingResponse response = bookingService.updateBooking(
                bookingId, 
                updateRequest, 
                managerId, 
                UserRole.MANAGER
        );

        // Then
        assertNotNull(response);
        assertEquals(BookingStatus.QUOTED, response.getStatus());
        verify(statusHistoryRepository, times(1)).save(argThat(history ->
            history.getOldStatus() == BookingStatus.PENDING &&
            history.getNewStatus() == BookingStatus.QUOTED &&
            history.getChangedByRole() == ActorRole.MANAGER
        ));
    }

    @Test
    void testUpdateBooking_Stage4_PendingAssignment() {
        // Given - AI analysis complete, waiting for manager to assign transport
        // In current system, booking stays in QUOTED status
        Long bookingId = 1L;
        
        mockBooking.setStatus(BookingStatus.QUOTED);
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));

        // When
        BookingResponse response = bookingService.getBookingById(bookingId, 1L, UserRole.CUSTOMER);

        // Then
        assertNotNull(response);
        assertEquals(BookingStatus.QUOTED, response.getStatus());
        verify(bookingRepository, times(1)).findById(bookingId);
    }

    @Test
    void testUpdateBooking_Stage5_Assigned() {
        // Given - Manager assigns transport to booking
        Long bookingId = 1L;
        Long managerId = 10L;
        Long transportId = 5L;
        
        mockBooking.setStatus(BookingStatus.QUOTED);
        
        Transport mockTransport = new Transport();
        mockTransport.setTransportId(transportId);
        mockTransport.setCompanyName("Transport Co.");
        
        User transportUser = new User();
        transportUser.setUserId(transportId);
        transportUser.setRole(UserRole.TRANSPORT);
        
        mockTransport.setUser(transportUser);
        
        BookingUpdateRequest updateRequest = new BookingUpdateRequest();
        updateRequest.setStatus(BookingStatus.CONFIRMED);
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setTransportId(transportId);
            return booking;
        });
        when(statusHistoryRepository.save(any(BookingStatusHistory.class)))
                .thenReturn(new BookingStatusHistory());
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(mockUser));

        // When
        BookingResponse response = bookingService.updateBooking(
                bookingId, 
                updateRequest, 
                managerId, 
                UserRole.MANAGER
        );

        // Then
        assertNotNull(response);
        assertEquals(BookingStatus.CONFIRMED, response.getStatus());
        verify(statusHistoryRepository, times(1)).save(argThat(history ->
            history.getNewStatus() == BookingStatus.CONFIRMED
        ));
    }

    @Test
    void testUpdateBooking_Stage6_InProgress() {
        // Given - Verify booking in IN_PROGRESS status (status change handled by other workflows)
        Long bookingId = 1L;
        Long customerId = 1L;
        
        mockBooking.setStatus(BookingStatus.IN_PROGRESS);
        mockBooking.setTransportId(5L);
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));

        // When - Get booking to verify status
        BookingResponse response = bookingService.getBookingById(bookingId, customerId, UserRole.CUSTOMER);

        // Then
        assertNotNull(response);
        assertEquals(BookingStatus.IN_PROGRESS, response.getStatus());
        assertEquals(5L, response.getTransportId());
        verify(bookingRepository, times(1)).findById(bookingId);
    }

    @Test
    void testUpdateBooking_Stage7_Completed() {
        // Given - Verify booking in COMPLETED status (status change handled by other workflows)
        Long bookingId = 1L;
        Long customerId = 1L;
        
        mockBooking.setStatus(BookingStatus.COMPLETED);
        mockBooking.setTransportId(5L);
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));

        // When - Get booking to verify status
        BookingResponse response = bookingService.getBookingById(bookingId, customerId, UserRole.CUSTOMER);

        // Then
        assertNotNull(response);
        assertEquals(BookingStatus.COMPLETED, response.getStatus());
        verify(bookingRepository, times(1)).findById(bookingId);
    }

    @Test
    void testUpdateBooking_Stage8_Closed() {
        // Given - Verify booking history to see the complete lifecycle
        Long bookingId = 1L;
        Long customerId = 1L;
        
        mockBooking.setStatus(BookingStatus.COMPLETED);
        mockBooking.setCustomerId(customerId);
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(statusHistoryRepository.findByBookingIdOrderByChangedAtDesc(bookingId))
                .thenReturn(java.util.Arrays.asList(
                    createHistory(bookingId, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED),
                    createHistory(bookingId, BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS),
                    createHistory(bookingId, BookingStatus.QUOTED, BookingStatus.CONFIRMED),
                    createHistory(bookingId, null, BookingStatus.PENDING)
                ));

        // When - Get booking history
        java.util.List<BookingStatusHistoryResponse> history = bookingService.getBookingHistory(
                bookingId, 
                customerId, 
                UserRole.CUSTOMER
        );

        // Then - Verify complete booking lifecycle
        assertNotNull(history);
        assertEquals(4, history.size());
        assertEquals(BookingStatus.COMPLETED, history.get(0).getNewStatus());
        verify(statusHistoryRepository, times(1)).findByBookingIdOrderByChangedAtDesc(bookingId);
    }

    private BookingStatusHistory createHistory(Long bookingId, BookingStatus oldStatus, BookingStatus newStatus) {
        BookingStatusHistory history = new BookingStatusHistory(
            bookingId, oldStatus, newStatus, 1L, ActorRole.SYSTEM
        );
        return history;
    }

    @Test
    void testCancelBooking_Success() {
        // Given
        Long bookingId = 1L;
        Long customerId = 1L;
        String reason = "Customer changed plans";
        
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancellationReason(reason);
            return booking;
        });
        when(statusHistoryRepository.save(any(BookingStatusHistory.class)))
                .thenReturn(new BookingStatusHistory());
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(mockUser));

        // When
        bookingService.cancelBooking(bookingId, reason, customerId, UserRole.CUSTOMER);

        // Then
        verify(bookingRepository, times(1)).save(argThat(booking ->
            booking.getStatus() == BookingStatus.CANCELLED &&
            reason.equals(booking.getCancellationReason())
        ));
        verify(statusHistoryRepository, times(1)).save(argThat(history ->
            history.getNewStatus() == BookingStatus.CANCELLED &&
            history.getReason().equals(reason)
        ));
    }
}
