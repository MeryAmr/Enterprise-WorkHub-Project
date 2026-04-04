package com.workhub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class ProjectResponse {
    private UUID id;
    private String name;
    private UUID tenantId;
    private UUID createdBy;
    private Instant createdAt;
}
