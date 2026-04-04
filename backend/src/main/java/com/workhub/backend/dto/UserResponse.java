package com.workhub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class UserResponse {
    private UUID id;
    private String email;
    private String role;
    private UUID tenantId;
    private String tenantName;
}
