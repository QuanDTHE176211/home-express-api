package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.BaseIntegrationTest;
import com.homeexpress.home_express_api.dto.booking.AddressDto;
import com.homeexpress.home_express_api.dto.booking.BookingRequest;
import com.homeexpress.home_express_api.dto.booking.BookingResponse;
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

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class BookingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private VnProvinceRepository provinceRepository;
    
    @Autowired
    private VnDistrictRepository districtRepository;
    
    @Autowired
    private VnWardRepository wardRepository;
    
    @Autowired
    private CategoryRepository categoryRepository;

    private User customerUser;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        // Seed Location Data
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
        
        // Delivery location (simulated as same for simplicity or add more)
        District district2 = new District();
        district2.setCode("769");
        district2.setName("Thu Duc");
        district2.setProvinceCode("79");
        districtRepository.save(district2);
        
        Ward ward2 = new Ward();
        ward2.setCode("27259");
        ward2.setName("Hiep Binh Chanh");
        ward2.setDistrictCode("769");
        wardRepository.save(ward2);

        // Seed Category
        Category category = new Category();
        category.setName("Test Category");
        category.setDescription("Test Description");
        category = categoryRepository.save(category);
        categoryId = category.getCategoryId();

        // Seed User
        customerUser = new User();
        customerUser.setEmail("test@example.com");
        customerUser.setPasswordHash("hashed_password");
        customerUser.setRole(UserRole.CUSTOMER);
        customerUser = userRepository.save(customerUser);
        
        Customer customer = new Customer();
        customer.setCustomerId(customerUser.getUserId());
        customer.setUser(customerUser); // Map user relation
        customer.setFullName("Test Customer");
        customer.setPhone("0901234567");
        customerRepository.save(customer);
    }

    @Test
    @WithMockUser(username = "test@example.com", roles = "CUSTOMER")
    void createBooking_ShouldPersistToDatabase() {
        // Arrange
        BookingRequest request = new BookingRequest();
        request.setPreferredDate(LocalDate.now().plusDays(2));
        request.setPreferredTimeSlot(TimeSlot.MORNING);
        request.setNotes("Integration Test Booking");

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
        delivery.setDistrictCode("769");
        delivery.setWardCode("27259");
        delivery.setLat(BigDecimal.valueOf(10.2));
        delivery.setLng(BigDecimal.valueOf(106.2));
        request.setDeliveryAddress(delivery);
        
        request.setItems(new ArrayList<>());
        BookingRequest.ItemDto item = new BookingRequest.ItemDto();
        item.setName("Test Item");
        item.setQuantity(1);
        item.setDeclaredValueVnd(BigDecimal.valueOf(100000));
        item.setCategoryId(categoryId);
        request.getItems().add(item);

        // Act
        BookingResponse response = bookingService.createBooking(request, customerUser.getUserId(), UserRole.CUSTOMER);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getBookingId());
        assertEquals(BookingStatus.PENDING, response.getStatus());
        assertEquals("Integration Test Booking", response.getNotes());
        assertEquals(customerUser.getUserId(), response.getCustomerId());
    }
}
