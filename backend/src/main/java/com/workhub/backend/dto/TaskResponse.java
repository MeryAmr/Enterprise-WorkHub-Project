package com.workhub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class TaskResponse {
    private UUID id;
    private String title;
    private String status;
    private UUID projectId;
    private UUID tenantId;
    private Instant createdAt;
}
