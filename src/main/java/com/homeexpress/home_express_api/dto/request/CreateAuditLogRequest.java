package com.homeexpress.home_express_api.dto.request;

import lombok.Data;

@Data
public class CreateAuditLogRequest {
    private String action;
    private String targetType;
    private Long targetId;
    private Object details;
}
