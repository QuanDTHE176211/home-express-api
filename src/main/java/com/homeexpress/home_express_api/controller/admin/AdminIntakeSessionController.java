package com.homeexpress.home_express_api.controller.admin;

import com.homeexpress.home_express_api.dto.intake.ItemCandidateDto;
import com.homeexpress.home_express_api.dto.intake.PublishSessionRequest;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionDetailResponse;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionListResponse;
import com.homeexpress.home_express_api.dto.response.AdminIntakeSessionStatsResponse;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;
import com.homeexpress.home_express_api.repository.UserRepository;
import com.homeexpress.home_express_api.service.intake.AdminIntakeSessionService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for managing intake sessions.
 * Provides endpoints for QA, override, and publishing intake sessions.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/sessions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class AdminIntakeSessionController {

    private final AdminIntakeSessionService adminIntakeSessionService;
    private final UserRepository userRepository;

    /**
     * List all intake sessions with pagination and filtering
     */
    @GetMapping
    public ResponseEntity<AdminIntakeSessionListResponse> listSessions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        AdminIntakeSessionListResponse response = adminIntakeSessionService.listSessions(
                status, customerId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Get session statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<AdminIntakeSessionStatsResponse> getSessionStats(
            @RequestParam(required = false, defaultValue = "NEEDS_REVIEW") String status,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        AdminIntakeSessionStatsResponse response = adminIntakeSessionService.getSessionStats(status);
        return ResponseEntity.ok(response);
    }

    /**
     * Get detailed intake session information
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSessionDetail(
            @PathVariable String id,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            AdminIntakeSessionDetailResponse session = adminIntakeSessionService.getSessionDetail(id);
            Map<String, Object> response = new HashMap<>();
            response.put("session", session);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching session detail: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Session not found: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * Get items for a specific intake session
     */
    @GetMapping("/{id}/items")
    public ResponseEntity<Map<String, Object>> getSessionItems(
            @PathVariable String id,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            List<ItemCandidateDto> items = adminIntakeSessionService.getSessionItems(id);
            Map<String, Object> response = new HashMap<>();
            response.put("items", items);
            response.put("count", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching session items: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to fetch items: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * Update session items (admin override)
     */
    @PutMapping("/{id}/items")
    public ResponseEntity<Map<String, Object>> updateSessionItems(
            @PathVariable String id,
            @Valid @RequestBody Map<String, List<ItemCandidateDto>> request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            List<ItemCandidateDto> items = request.get("items");
            if (items == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Items array is required");
                return ResponseEntity.badRequest().body(error);
            }

            adminIntakeSessionService.updateSessionItems(id, items, user.getUserId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Items updated successfully");
            response.put("itemCount", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating session items: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to update items: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Rerun AI detection for a session
     */
    @PostMapping("/{id}/rerun")
    public ResponseEntity<Map<String, Object>> rerunAIDetection(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            boolean forceReprocess = request != null && Boolean.TRUE.equals(request.get("forceReprocess"));
            adminIntakeSessionService.rerunAIDetection(id, forceReprocess, user.getUserId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "AI detection rerun initiated. Check logs via SSE endpoint.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error rerunning AI detection: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to rerun AI detection: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Force a custom quote for a session (admin override)
     */
    @PostMapping("/{id}/force-quote")
    public ResponseEntity<Map<String, Object>> forceQuote(
            @PathVariable String id,
            @Valid @RequestBody Map<String, Object> request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Object priceObj = request.get("price");
            if (priceObj == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Price is required");
                return ResponseEntity.badRequest().body(error);
            }

            double price = priceObj instanceof Number ? ((Number) priceObj).doubleValue() : Double.parseDouble(priceObj.toString());
            String notes = request.get("notes") != null ? request.get("notes").toString() : null;

            adminIntakeSessionService.forceQuote(id, price, notes, user.getUserId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Forced quote applied successfully");
            response.put("price", price);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error forcing quote: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to force quote: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Publish session to booking (create booking from intake session)
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publishSession(
            @PathVariable String id,
            @RequestBody(required = false) PublishSessionRequest request,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            PublishSessionRequest effectiveRequest = request != null ? request : new PublishSessionRequest();
            Long bookingId = adminIntakeSessionService.publishSession(id, effectiveRequest, user.getUserId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Session published to booking successfully");
            response.put("bookingId", bookingId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error publishing session: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to publish session: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * SSE endpoint for real-time log streaming during AI processing
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSessionEvents(
            @PathVariable String id,
            Authentication authentication) {
        
        User user = AuthenticationUtils.getUser(authentication, userRepository);
        if (user.getRole() != UserRole.MANAGER) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\":\"Unauthorized\"}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        return adminIntakeSessionService.createEventStream(id);
    }
}

