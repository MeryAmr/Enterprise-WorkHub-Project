package com.workhub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class GenerateReportResponse {
    private UUID reportId;
    private String status;
}
