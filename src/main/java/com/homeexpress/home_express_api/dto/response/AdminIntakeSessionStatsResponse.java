package com.homeexpress.home_express_api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminIntakeSessionStatsResponse {
    private Long total;
    private Double avgConfidence;
    private Long oldestWaitTimeSeconds;
}
