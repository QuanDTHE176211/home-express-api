package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.ai.DetectionResult;
import com.homeexpress.home_express_api.service.ai.AIDetectionOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API Controller for AI Image Detection
 * 
 * Endpoints:
 * - POST /api/ai/detect-items - Detect items from images
 */
@RestController
@RequestMapping("/api/ai")
public class AIDetectionController {

    private static final Logger log = LoggerFactory.getLogger(AIDetectionController.class);

    private final AIDetectionOrchestrator detectionOrchestrator;

    @Autowired
    public AIDetectionController(AIDetectionOrchestrator detectionOrchestrator) {
        this.detectionOrchestrator = detectionOrchestrator;
    }

    /**
     * Detect items from images using AI approach
     * 
     * POST /api/ai/detect-items
     * 
     * Request body:
     * {
     *   "imageUrls": [
     *     "https://example.com/image1.jpg",
     *     "https://example.com/image2.jpg"
     *   ]
     * }
     * 
     * Response:
     * {
     *   "items": [...],
     *   "confidence": 0.92,
     *   "serviceUsed": "OPENAI_VISION",
     *   "fallbackUsed": false,
     *   "processingTimeMs": 2500
     * }
     */
    @PostMapping("/detect-items")
    public ResponseEntity<?> detectItems(@Valid @RequestBody DetectionRequest request) {
        try {
            log.info("Received detection request for {} images", request.getImageUrls().size());
            
            // Validate image URLs
            if (request.getImageUrls().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "error", "VALIDATION_ERROR",
                        "message", "At least one image URL is required"
                    ));
            }
            
            if (request.getImageUrls().size() > 20) {
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "error", "VALIDATION_ERROR",
                        "message", "Maximum 20 images allowed per request"
                    ));
            }
            
            // Perform detection
            DetectionResult result = detectionOrchestrator.detectItems(request.getImageUrls());
            int itemCount = result.getItems() != null ? result.getItems().size() : 0;
            int enhancedCount = result.getEnhancedItems() != null ? result.getEnhancedItems().size() : 0;

            log.info("Detection completed - Service: {} | Items: {} | Enhanced: {} | Confidence: {:.2f}% | Latency: {}ms",
                result.getServiceUsed(), 
                itemCount,
                enhancedCount,
                (result.getConfidence() != null ? result.getConfidence() * 100 : 0.0),
                result.getProcessingTimeMs());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Detection failed: {}", e.getMessage(), e);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", "DETECTION_FAILED",
                    "message", "Failed to detect items: " + e.getMessage()
                ));
        }
    }
    
    /**
     * Health check endpoint
     * 
     * GET /api/ai/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "AI Detection API",
            "version", "2.0-simplified"
        ));
    }

    /**
     * Reset GPT-5 mini budget counters (for testing/admin use)
     * 
     * POST /api/ai/reset-budget
     */
    @PostMapping("/reset-budget")
    public ResponseEntity<?> resetBudget(@RequestParam(defaultValue = "hourly") String scope) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", "Budget tracking has been disabled"));
    }
    
    // Request DTO
    public static class DetectionRequest {
        
        @NotEmpty(message = "Image URLs are required")
        @Size(min = 1, max = 20, message = "Between 1 and 20 images allowed")
        private List<String> imageUrls;

        public List<String> getImageUrls() {
            return imageUrls;
        }

        public void setImageUrls(List<String> imageUrls) {
            this.imageUrls = imageUrls;
        }
    }
}
