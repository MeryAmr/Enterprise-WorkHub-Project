package com.workhub.backend.controller;

import com.workhub.backend.dto.GenerateReportResponse;
import com.workhub.backend.dto.ReportResponse;
import com.workhub.backend.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN','TENANT_USER')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/projects/{projectId}/generate-report")
    public ResponseEntity<GenerateReportResponse> generateReport(
            @PathVariable UUID projectId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(reportService.enqueue(userId, projectId));
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<ReportResponse> getReport(@PathVariable UUID id) {
        return ResponseEntity.ok(reportService.getReport(id));
    }
}
