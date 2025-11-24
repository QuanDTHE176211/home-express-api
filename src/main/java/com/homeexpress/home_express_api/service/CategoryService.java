package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.dto.request.CategoryRequest;
import com.homeexpress.home_express_api.dto.request.SizeRequest;
import com.homeexpress.home_express_api.entity.Category;
import com.homeexpress.home_express_api.entity.Size;
import com.homeexpress.home_express_api.entity.BookingStatus;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.CategoryRepository;
import com.homeexpress.home_express_api.repository.SizeRepository;
import com.homeexpress.home_express_api.repository.BookingItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    private final SizeRepository sizeRepository;

    private final BookingItemRepository bookingItemRepository;

    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    public List<Category> getActiveCategories() {
        return categoryRepository.findByIsActive(true);
    }

    public Category getCategoryById(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", categoryId));
    }

    @Transactional
    public Category createCategory(CategoryRequest request) {
        if (categoryRepository.existsByName(request.getName())) {
            throw new RuntimeException("Category with name '" + request.getName() + "' already exists");
        }

        Category category = new Category();
        category.setName(request.getName());
        category.setNameEn(request.getNameEn());
        category.setDescription(request.getDescription());
        category.setIcon(request.getIcon());
        category.setDefaultWeightKg(request.getDefaultWeightKg());
        category.setDefaultVolumeM3(request.getDefaultVolumeM3());
        category.setDefaultLengthCm(request.getDefaultLengthCm());
        category.setDefaultWidthCm(request.getDefaultWidthCm());
        category.setDefaultHeightCm(request.getDefaultHeightCm());
        category.setIsFragileDefault(request.getIsFragileDefault() != null ? request.getIsFragileDefault() : false);
        category.setRequiresDisassemblyDefault(request.getRequiresDisassemblyDefault() != null ? request.getRequiresDisassemblyDefault() : false);
        category.setDisplayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0);
        category.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        return categoryRepository.save(category);
    }

    @Transactional
    public Category updateCategory(Long categoryId, CategoryRequest request) {
        Category category = getCategoryById(categoryId);

        if (request.getName() != null && !request.getName().equals(category.getName())) {
            if (categoryRepository.existsByName(request.getName())) {
                throw new RuntimeException("Category with name '" + request.getName() + "' already exists");
            }
            category.setName(request.getName());
        }

        if (request.getNameEn() != null) category.setNameEn(request.getNameEn());
        if (request.getDescription() != null) category.setDescription(request.getDescription());
        if (request.getIcon() != null) category.setIcon(request.getIcon());
        if (request.getDefaultWeightKg() != null) category.setDefaultWeightKg(request.getDefaultWeightKg());
        if (request.getDefaultVolumeM3() != null) category.setDefaultVolumeM3(request.getDefaultVolumeM3());
        if (request.getDefaultLengthCm() != null) category.setDefaultLengthCm(request.getDefaultLengthCm());
        if (request.getDefaultWidthCm() != null) category.setDefaultWidthCm(request.getDefaultWidthCm());
        if (request.getDefaultHeightCm() != null) category.setDefaultHeightCm(request.getDefaultHeightCm());
        if (request.getIsFragileDefault() != null) category.setIsFragileDefault(request.getIsFragileDefault());
        if (request.getRequiresDisassemblyDefault() != null) category.setRequiresDisassemblyDefault(request.getRequiresDisassemblyDefault());
        if (request.getDisplayOrder() != null) category.setDisplayOrder(request.getDisplayOrder());
        if (request.getIsActive() != null) category.setIsActive(request.getIsActive());

        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = getCategoryById(categoryId);
        category.setIsActive(false);
        categoryRepository.save(category);
    }

    @Transactional
    public Size addSizeToCategory(Long categoryId, SizeRequest request) {
        Category category = getCategoryById(categoryId);

        Size size = new Size();
        size.setCategory(category);
        size.setName(request.getName());
        size.setWeightKg(request.getWeightKg());
        size.setHeightCm(request.getHeightCm());
        size.setWidthCm(request.getWidthCm());
        size.setDepthCm(request.getDepthCm());
        size.setPriceMultiplier(request.getPriceMultiplier());

        return sizeRepository.save(size);
    }

    public List<Size> getSizesByCategory(Long categoryId) {
        return sizeRepository.findByCategory_CategoryId(categoryId);
    }

    @Transactional
    public void deleteSize(Long sizeId) {
        if (!sizeRepository.existsById(sizeId)) {
            throw new ResourceNotFoundException("Size", "id", sizeId);
        }
        sizeRepository.deleteById(sizeId);
    }

    /**
     * Check if a category is being used in bookings
     */
    public Map<String, Object> checkCategoryUsage(Long categoryId) {
        getCategoryById(categoryId);
        
        // Count total items using this category
        Long totalItems = bookingItemRepository.countByCategoryCategoryId(categoryId);
        
        // Count items in active bookings
        Long activeBookings = bookingItemRepository.countByCategoryCategoryIdAndBookingStatusIn(
            categoryId,
            List.of(
                BookingStatus.PENDING,
                BookingStatus.QUOTED,
                BookingStatus.CONFIRMED,
                BookingStatus.IN_PROGRESS
            )
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("isInUse", totalItems > 0);
        result.put("totalItems", totalItems);
        result.put("activeBookings", activeBookings);
        result.put("usageCount", totalItems);
        
        return result;
    }
}

