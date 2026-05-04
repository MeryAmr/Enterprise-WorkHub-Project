package com.workhub.backend.service;

import com.workhub.backend.dto.GenerateReportResponse;
import com.workhub.backend.dto.ReportResponse;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Report;
import com.workhub.backend.entity.ReportStatus;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.messaging.ReportJobMessage;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.ReportRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ReportProducer reportProducer;

    public ReportService(ReportRepository reportRepository,
                         ProjectRepository projectRepository,
                         UserRepository userRepository,
                         ReportProducer reportProducer) {
        this.reportRepository = reportRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.reportProducer = reportProducer;
    }

    /**
     * Creates a PENDING Report row and publishes a Kafka job AFTER the DB transaction
     * commits. Publishing post-commit prevents the consumer from racing ahead and not
     * finding the Report row.
     */
    @Transactional
    public GenerateReportResponse enqueue(UUID userId, UUID projectId) {
        UUID tenantId = TenantContext.getTenantId();

        Project project = projectRepository.findByIdAndTenant_Id(projectId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        Report report = reportRepository.save(
                Report.builder()
                        .tenant(project.getTenant())
                        .project(project)
                        .requestedBy(userRepository.getReferenceById(userId))
                        .status(ReportStatus.PENDING)
                        .build()
        );

        ReportJobMessage msg = new ReportJobMessage(
                UUID.randomUUID(), tenantId, projectId, report.getId(), userId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reportProducer.publish(msg);
            }
        });

        return GenerateReportResponse.builder()
                .reportId(report.getId())
                .status(report.getStatus().name())
                .build();
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(UUID reportId) {
        return reportRepository.findByIdAndTenant_Id(reportId, TenantContext.getTenantId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
    }

    private ReportResponse toResponse(Report report) {
        return ReportResponse.builder()
                .id(report.getId())
                .projectId(report.getProject().getId())
                .tenantId(report.getTenant().getId())
                .status(report.getStatus().name())
                .payload(report.getPayload())
                .createdAt(report.getCreatedAt())
                .completedAt(report.getCompletedAt())
                .build();
    }
}
