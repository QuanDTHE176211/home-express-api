package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.CategoryRequest;
import com.homeexpress.home_express_api.dto.request.SizeRequest;
import com.homeexpress.home_express_api.entity.Category;
import com.homeexpress.home_express_api.entity.Size;
import com.homeexpress.home_express_api.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/categories")
public class CategoryManagementController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'TRANSPORT', 'CUSTOMER')")
    public ResponseEntity<List<Category>> getAllCategories(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        
        List<Category> categories;
        if (activeOnly) {
            categories = categoryService.getActiveCategories();
        } else {
            categories = categoryService.getAllCategories();
        }
        
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Category> getCategoryById(@PathVariable Long id) {
        Category category = categoryService.getCategoryById(id);
        return ResponseEntity.ok(category);
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Category> createCategory(@Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(category);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Category> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CategoryRequest request) {
        
        Category category = categoryService.updateCategory(id, request);
        return ResponseEntity.ok(category);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Category deactivated successfully");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{categoryId}/sizes")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<List<Size>> getCategorySizes(@PathVariable Long categoryId) {
        List<Size> sizes = categoryService.getSizesByCategory(categoryId);
        return ResponseEntity.ok(sizes);
    }

    @PostMapping("/{categoryId}/sizes")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Size> addSizeToCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody SizeRequest request) {
        
        Size size = categoryService.addSizeToCategory(categoryId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(size);
    }

    @DeleteMapping("/sizes/{sizeId}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> deleteSize(@PathVariable Long sizeId) {
        categoryService.deleteSize(sizeId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Size deleted successfully");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{categoryId}/usage")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> checkCategoryUsage(@PathVariable Long categoryId) {
        Map<String, Object> usageData = categoryService.checkCategoryUsage(categoryId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", usageData);
        
        return ResponseEntity.ok(response);
    }
}

