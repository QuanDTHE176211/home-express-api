package com.homeexpress.home_express_api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing Server-Sent Events (SSE) connections for customer bookings.
 * Provides real-time updates for booking status, quotations, payments, and transport assignment.
 */
@Slf4j
@Service
public class CustomerEventService {

    private final ObjectMapper objectMapper;

    // Map of booking ID to map of emitter ID to SseEmitter
    // This allows multiple customers to watch the same booking (e.g., shared bookings)
    private final Map<Long, Map<String, SseEmitter>> bookingEmitters = new ConcurrentHashMap<>();

    public CustomerEventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new SSE connection for a booking
     * @param bookingId The booking to watch
     * @param customerId The customer ID (used as emitter key)
     * @return SseEmitter for the connection
     */
    public SseEmitter createEventStream(Long bookingId, Long customerId) {
        // 30 minutes timeout (1800000ms)
        SseEmitter emitter = new SseEmitter(1800000L);
        String emitterKey = customerId.toString();

        // Add emitter to the map
        bookingEmitters.computeIfAbsent(bookingId, k -> new ConcurrentHashMap<>())
                .put(emitterKey, emitter);

        log.info("Created SSE connection for customer {} on booking {}", customerId, bookingId);

        // Setup cleanup handlers
        emitter.onCompletion(() -> {
            removeEmitter(bookingId, emitterKey);
            log.debug("SSE connection completed for customer {} on booking {}", customerId, bookingId);
        });

        emitter.onTimeout(() -> {
            removeEmitter(bookingId, emitterKey);
            log.debug("SSE connection timeout for customer {} on booking {}", customerId, bookingId);
        });

        emitter.onError((ex) -> {
            removeEmitter(bookingId, emitterKey);
            log.error("SSE connection error for customer {} on booking {}: {}", 
                    customerId, bookingId, ex.getMessage());
        });

        // Send initial connection message
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(createEventData("connected", "Connected to booking updates", null)));
        } catch (IOException e) {
            log.error("Error sending initial SSE message: {}", e.getMessage());
            removeEmitter(bookingId, emitterKey);
        }

        return emitter;
    }

    /**
     * Send booking status change event
     */
    public void sendBookingStatusUpdate(Long bookingId, String oldStatus, String newStatus, String message) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "oldStatus", oldStatus != null ? oldStatus : "",
                "newStatus", newStatus,
                "message", message != null ? message : ""
        );

        sendEvent(bookingId, "booking:status_changed", "Booking status updated", data);
    }

    /**
     * Send new quotation received event
     */
    public void sendNewQuotation(Long bookingId, Long quotationId, Long transportId, 
                                  String transportName, Long priceVnd) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "quotationId", quotationId,
                "transportId", transportId,
                "transportName", transportName,
                "priceVnd", priceVnd
        );

        sendEvent(bookingId, "booking:quotation_received", "New quotation received", data);
    }

    /**
     * Send payment completed event
     */
    public void sendPaymentUpdate(Long bookingId, Long paymentId, String paymentType, 
                                   Long amountVnd, String status) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "paymentId", paymentId,
                "paymentType", paymentType,
                "amountVnd", amountVnd,
                "status", status
        );

        sendEvent(bookingId, "booking:payment_completed", "Payment completed", data);
    }

    /**
     * Send transport assignment event
     */
    public void sendTransportAssignment(Long bookingId, Long transportId, String transportName, 
                                        String contactPhone) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "transportId", transportId,
                "transportName", transportName,
                "contactPhone", contactPhone != null ? contactPhone : ""
        );

        sendEvent(bookingId, "booking:transport_assigned", "Transport assigned", data);
    }

    /**
     * Send heartbeat to keep connection alive
     */
    public void sendHeartbeat(Long bookingId) {
        sendEvent(bookingId, "heartbeat", "ping", Map.of("timestamp", LocalDateTime.now().toString()));
    }

    /**
     * Send dispute update event
     * @param customerId The customer ID to send the event to
     * @param dispute The dispute data
     * @param eventType The type of event (dispute_created, dispute_status_changed, dispute_message_added)
     */
    public void sendDisputeUpdate(Long customerId, Object dispute, String eventType) {
        // For disputes, we need to send to the booking's customer
        // The dispute object should contain bookingId
        try {
            String disputeJson = objectMapper.writeValueAsString(dispute);
            Map<String, Object> disputeMap = objectMapper.readValue(disputeJson, new TypeReference<Map<String, Object>>() {});
            Long bookingId = ((Number) disputeMap.get("bookingId")).longValue();

            Map<String, Object> data = Map.of(
                    "dispute", disputeMap,
                    "eventType", eventType
            );

            sendEvent(bookingId, "dispute:" + eventType, "Dispute update", data);
        } catch (Exception e) {
            log.error("Error sending dispute update event: {}", e.getMessage());
        }
    }

    /**
     * Send counter-offer created event
     */
    public void sendCounterOfferCreated(Long bookingId, Long counterOfferId) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "counterOfferId", counterOfferId,
                "action", "created"
        );

        sendEvent(bookingId, "counter_offer:created", "New counter-offer created", data);
    }

    /**
     * Send counter-offer accepted event
     */
    public void sendCounterOfferAccepted(Long bookingId, Long counterOfferId) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "counterOfferId", counterOfferId,
                "action", "accepted"
        );

        sendEvent(bookingId, "counter_offer:accepted", "Counter-offer accepted", data);
    }

    /**
     * Send counter-offer rejected event
     */
    public void sendCounterOfferRejected(Long bookingId, Long counterOfferId) {
        Map<String, Object> data = Map.of(
                "bookingId", bookingId,
                "counterOfferId", counterOfferId,
                "action", "rejected"
        );

        sendEvent(bookingId, "counter_offer:rejected", "Counter-offer rejected", data);
    }

    /**
     * Generic method to send an event to all emitters watching a booking
     */
    private void sendEvent(Long bookingId, String eventName, String message, Map<String, Object> data) {
        Map<String, SseEmitter> emitters = bookingEmitters.get(bookingId);
        
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE connections for booking {}", bookingId);
            return;
        }

        String eventData = createEventData(eventName, message, data);
        
        // Send to all emitters, removing failed ones
        emitters.entrySet().removeIf(entry -> {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(eventName)
                        .data(eventData));
                return false; // Keep this emitter
            } catch (IOException e) {
                log.error("Error sending SSE event to emitter {}: {}", entry.getKey(), e.getMessage());
                try {
                    entry.getValue().completeWithError(e);
                } catch (Exception ex) {
                    // Ignore
                }
                return true; // Remove this emitter
            }
        });

        log.debug("Sent SSE event '{}' to {} connections for booking {}", 
                eventName, emitters.size(), bookingId);
    }

    /**
     * Create JSON event data
     */
    private String createEventData(String type, String message, Map<String, Object> data) {
        try {
            Map<String, Object> eventData = new ConcurrentHashMap<>();
            eventData.put("type", type);
            eventData.put("message", message);
            eventData.put("timestamp", LocalDateTime.now().toString());
            
            if (data != null) {
                eventData.put("data", data);
            }
            
            return objectMapper.writeValueAsString(eventData);
        } catch (Exception e) {
            log.error("Error creating event data: {}", e.getMessage());
            return String.format("{\"type\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}", 
                    type, message, LocalDateTime.now());
        }
    }

    /**
     * Remove an emitter from the map
     */
    private void removeEmitter(Long bookingId, String emitterKey) {
        Map<String, SseEmitter> emitters = bookingEmitters.get(bookingId);
        if (emitters != null) {
            emitters.remove(emitterKey);
            
            // Clean up empty booking entries
            if (emitters.isEmpty()) {
                bookingEmitters.remove(bookingId);
                log.debug("Removed empty emitter map for booking {}", bookingId);
            }
        }
    }

    /**
     * Get count of active connections for a booking
     */
    public int getActiveConnectionCount(Long bookingId) {
        Map<String, SseEmitter> emitters = bookingEmitters.get(bookingId);
        return emitters != null ? emitters.size() : 0;
    }

    /**
     * Get total count of active connections across all bookings
     */
    public int getTotalActiveConnections() {
        return bookingEmitters.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    /**
     * Close all connections for a booking
     */
    public void closeAllConnections(Long bookingId) {
        Map<String, SseEmitter> emitters = bookingEmitters.remove(bookingId);
        if (emitters != null) {
            emitters.values().forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // Ignore
                }
            });
            log.info("Closed {} SSE connections for booking {}", emitters.size(), bookingId);
        }
    }
}

