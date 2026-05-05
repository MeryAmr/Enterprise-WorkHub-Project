package com.workhub.backend.service;

import com.workhub.backend.dto.GenerateReportResponse;
import com.workhub.backend.dto.ReportResponse;
import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Report;
import com.workhub.backend.entity.ReportStatus;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.ReportRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ReportRepository  reportRepository;
    @Mock ProjectRepository projectRepository;
    @Mock UserRepository    userRepository;
    @Mock ReportProducer    reportProducer;
    @InjectMocks ReportService reportService;

    static final UUID TENANT_ID  = UUID.randomUUID();
    static final UUID USER_ID    = UUID.randomUUID();
    static final UUID PROJECT_ID = UUID.randomUUID();
    static final UUID REPORT_ID  = UUID.randomUUID();

    Tenant  tenant;
    Project project;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        tenant  = Tenant.builder().id(TENANT_ID).name("Acme Corp").plan(Plan.FREE).build();
        User creator = User.builder().id(USER_ID).email("admin@acme.com").build();
        project = Project.builder().id(PROJECT_ID).tenant(tenant).name("Test Project").createdBy(creator).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── enqueue ───────────────────────────────────────────────────────────────

    @Test
    void enqueue_success() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        Report saved = Report.builder().id(REPORT_ID).tenant(tenant).project(project).build();
        when(reportRepository.save(any())).thenReturn(saved);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                     mockStatic(TransactionSynchronizationManager.class)) {

            GenerateReportResponse response = reportService.enqueue(USER_ID, PROJECT_ID);

            assertThat(response.getReportId()).isEqualTo(REPORT_ID);
            assertThat(response.getStatus()).isEqualTo("PENDING");
            tsm.verify(() -> TransactionSynchronizationManager
                    .registerSynchronization(any(TransactionSynchronization.class)));
        }
    }

    @Test
    void enqueue_projectNotFound_throwsResourceNotFoundException() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());

        try (MockedStatic<TransactionSynchronizationManager> _ =
                     mockStatic(TransactionSynchronizationManager.class)) {

            assertThatThrownBy(() -> reportService.enqueue(USER_ID, PROJECT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Project not found");
        }
    }

    // ── getReport ─────────────────────────────────────────────────────────────

    @Test
    void getReport_success() {
        Report report = Report.builder()
                .id(REPORT_ID).tenant(tenant).project(project)
                .status(ReportStatus.COMPLETED).payload("report content")
                .build();
        when(reportRepository.findByIdAndTenant_Id(REPORT_ID, TENANT_ID)).thenReturn(Optional.of(report));

        ReportResponse response = reportService.getReport(REPORT_ID);

        assertThat(response.getId()).isEqualTo(REPORT_ID);
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getPayload()).isEqualTo("report content");
        assertThat(response.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(response.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void getReport_notFound_throwsResourceNotFoundException() {
        when(reportRepository.findByIdAndTenant_Id(REPORT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReport(REPORT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Report not found");
    }
}
