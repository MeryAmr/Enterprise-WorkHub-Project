package com.workhub.backend.integration;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Report;
import com.workhub.backend.entity.ReportStatus;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.messaging.ReportJobMessage;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProcessedMessageRepository;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.ReportRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Section E: Messaging Reliability (PDF Section 6.E).
// Testcontainers Kafka + Awaitility. Publishes the same messageId twice;
// asserts consumer processed exactly once via ProcessedMessage idempotency table.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MessagingReliabilityIT {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void overrideKafkaBootstrap(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired ReportProducer             reportProducer;
    @Autowired ReportRepository           reportRepository;
    @Autowired ProcessedMessageRepository processedMessageRepository;
    @Autowired TenantRepository           tenantRepository;
    @Autowired UserRepository             userRepository;
    @Autowired ProjectRepository          projectRepository;

    Tenant  tenant;
    User    user;
    Project project;
    Report  report;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        tenant = tenantRepository.save(
                Tenant.builder().name("Messaging Tenant " + suffix).plan(Plan.FREE).build());

        user = userRepository.save(User.builder()
                .email("messaging-admin-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        project = projectRepository.save(Project.builder()
                .name("Messaging Project " + suffix)
                .tenant(tenant)
                .createdBy(user)
                .build());

        report = reportRepository.save(Report.builder()
                .tenant(tenant)
                .project(project)
                .requestedBy(user)
                .status(ReportStatus.PENDING)
                .build());
    }

    @AfterEach
    void tearDown() {
        processedMessageRepository.deleteAll();
        reportRepository.deleteById(report.getId());
        projectRepository.deleteById(project.getId());
        userRepository.deleteById(user.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    @Test
    void duplicateMessage_idempotency_processedExactlyOnce() throws InterruptedException {
        UUID messageId = UUID.randomUUID();
        ReportJobMessage msg = new ReportJobMessage(
                messageId, tenant.getId(), project.getId(), report.getId(), user.getId());

        // Publish SAME messageId twice → consumer must process only once
        reportProducer.publish(msg);
        reportProducer.publish(msg);

        // Awaitility: wait until consumer processed first message (report → COMPLETED)
        await().atMost(15, SECONDS).untilAsserted(() -> {
            Report r = reportRepository.findById(report.getId()).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(ReportStatus.COMPLETED);
            assertThat(r.getPayload()).isNotBlank();
        });

        // Allow consumer thread to also receive + skip the duplicate
        Thread.sleep(2000);

        // Idempotency proof: exactly 1 processed_messages row for this messageId
        assertThat(processedMessageRepository.existsById(messageId)).isTrue();
        assertThat(processedMessageRepository.count()).isEqualTo(1);

        // Report still COMPLETED (duplicate did NOT re-process or corrupt payload)
        Report finalReport = reportRepository.findById(report.getId()).orElseThrow();
        assertThat(finalReport.getStatus()).isEqualTo(ReportStatus.COMPLETED);
    }
}
