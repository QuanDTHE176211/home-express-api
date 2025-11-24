package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.entity.BookingItem;
import com.homeexpress.home_express_api.entity.Category;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.BookingItemRepository;
import com.homeexpress.home_express_api.repository.BookingRepository;
import com.homeexpress.home_express_api.repository.CategoryRepository;
import jakarta.transaction.Transactional;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles persistence of booking items, including AI-detected payloads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingItemService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<BookingItem> getBookingItems(Long bookingId) {
        bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        return bookingItemRepository.findByBookingId(bookingId);
    }
}
