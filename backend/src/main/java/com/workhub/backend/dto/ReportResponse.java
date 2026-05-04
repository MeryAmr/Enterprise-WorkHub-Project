package com.workhub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class ReportResponse {
    private UUID id;
    private UUID projectId;
    private UUID tenantId;
    private String status;
    private String payload;
    private Instant createdAt;
    private Instant completedAt;
}
