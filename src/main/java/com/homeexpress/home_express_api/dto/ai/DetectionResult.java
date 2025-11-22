package com.homeexpress.home_express_api.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Complete result from AI image detection process
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionResult {
    
    /**
     * List of detected items
     */
    private List<DetectedItem> items;

    /**
     * Enhanced item payloads (if provided by AI models)
     */
    @Builder.Default
    private List<EnhancedDetectedItem> enhancedItems = List.of();

    /**
     * Overall confidence score (average of all items)
     */
    private Double confidence;
    
    /** AI service used (OPENAI_VISION, MANUAL, etc.) */
    private String serviceUsed;
    
    /**
     * Whether fallback to secondary service was used
     */
    @Builder.Default
    private Boolean fallbackUsed = false;
    
    /**
     * Original confidence before GPT-4 enhancement (if applicable)
     */
    private Double originalConfidence;
    
    /**
     * Processing time in milliseconds
     */
    private Long processingTimeMs;
    
    /**
     * Whether manual input is required (all AI services failed)
     */
    @Builder.Default
    private Boolean manualInputRequired = false;
    
    /**
     * Whether manual review is recommended (low confidence)
     */
    @Builder.Default
    private Boolean manualReviewRequired = false;
    
    /**
     * Failure reason (if applicable)
     */
    private String failureReason;
    
    /**
     * Timestamp of detection
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * Cache hit indicator
     */
    @Builder.Default
    private Boolean fromCache = false;
    
    /**
     * Number of images processed
     */
    private Integer imageCount;
    
    /**
     * List of image URLs processed
     */
    private List<String> imageUrls;

    public List<DetectedItem> getItems() {
        return items;
    }

    public List<EnhancedDetectedItem> getEnhancedItems() {
        return enhancedItems;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getServiceUsed() {
        return serviceUsed;
    }

    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }
}
