package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.CreateAuditLogRequest;
import com.homeexpress.home_express_api.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @PostMapping
    public ResponseEntity<Void> createAuditLog(@RequestBody CreateAuditLogRequest request) {
        auditService.createAuditLog(request);
        return ResponseEntity.ok().build();
    }
}
