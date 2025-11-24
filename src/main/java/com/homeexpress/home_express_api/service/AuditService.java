package com.homeexpress.home_express_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.request.CreateAuditLogRequest;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void createAuditLog(CreateAuditLogRequest request) {
        Long currentUserId = AuthenticationUtils.getUserId(SecurityContextHolder.getContext().getAuthentication());
        
        String detailsJson = null;
        if (request.getDetails() != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(request.getDetails());
            } catch (Exception e) {
                // If serialization fails, we just store it as null or maybe toString?
                // For now let's keep it null to avoid storing garbage
            }
        }

        String tableName = mapTargetTypeToTableName(request.getTargetType());

        String sql = "INSERT INTO audit_log (table_name, action, row_pk, actor_id, new_data) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, tableName, request.getAction(), request.getTargetId(), currentUserId, detailsJson);
    }

    private String mapTargetTypeToTableName(String targetType) {
        if (targetType == null) return "unknown";
        return switch (targetType.toUpperCase()) {
            case "USER" -> "users";
            case "TRANSPORT" -> "transports";
            case "CATEGORY" -> "categories";
            case "REVIEW" -> "reviews";
            case "OUTBOX_EVENT" -> "outbox_events";
            case "BID" -> "bids";
            case "EXCEPTION" -> "exceptions";
            case "BOOKING" -> "bookings";
            case "QUOTATION" -> "quotations";
            default -> targetType.toLowerCase();
        };
    }
}
