package com.workhub.backend.messaging;

import com.workhub.backend.entity.ProcessedMessage;
import com.workhub.backend.entity.Report;
import com.workhub.backend.entity.ReportStatus;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.TaskStatus;
import com.workhub.backend.repository.ProcessedMessageRepository;
import com.workhub.backend.repository.ReportRepository;
import com.workhub.backend.repository.TaskRepository;
import com.workhub.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.workhub.backend.messaging.KafkaTopics.REPORTS_GENERATE;

@Component
public class ReportConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportConsumer.class);

    private final ReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final ReportConsumer self;

    public ReportConsumer(ReportRepository reportRepository,
                          TaskRepository taskRepository,
                          ProcessedMessageRepository processedMessageRepository,
                          @org.springframework.context.annotation.Lazy ReportConsumer self) {
        this.reportRepository = reportRepository;
        this.taskRepository = taskRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.self = self;
    }

    @KafkaListener(topics = REPORTS_GENERATE,
                   groupId = "${spring.kafka.consumer.group-id}",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ReportJobMessage msg) {
        // Cheap pre-transaction check: skip if already processed.
        // Avoids triggering rollback-only state on duplicate inserts within @Transactional.
        if (processedMessageRepository.existsById(msg.messageId())) {
            log.info("Duplicate message skipped (existsById): messageId={}", msg.messageId());
            return;
        }

        // Consumer thread has no HTTP request — set TenantContext from message body.
        TenantContext.setTenantId(msg.tenantId());
        try {
            self.process(msg);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public void process(ReportJobMessage msg) {
        // Race-safety net: another thread may have inserted between check and here.
        // PK violation here triggers rollback of this transaction; the @KafkaListener
        // method will see the row on the retry and short-circuit via existsById.
        try {
            processedMessageRepository.saveAndFlush(
                    ProcessedMessage.builder().messageId(msg.messageId()).build()
            );
        } catch (DataIntegrityViolationException _) {
            log.info("Duplicate message skipped (race on insert): messageId={}", msg.messageId());
            throw new IllegalStateException("duplicate-skip");
        }

        Report report = reportRepository.findByIdAndTenant_Id(msg.reportId(), msg.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Report not found for tenant " + msg.tenantId()
                                + " id " + msg.reportId()));

        List<Task> tasks = taskRepository.findAllByProject_IdAndTenant_Id(
                msg.projectId(), msg.tenantId());

        Map<TaskStatus, Long> counts = tasks.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));

        String payload = String.format(
                "Project report — total tasks: %d | TODO: %d | IN_PROGRESS: %d | DONE: %d",
                tasks.size(),
                counts.getOrDefault(TaskStatus.TODO, 0L),
                counts.getOrDefault(TaskStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(TaskStatus.DONE, 0L)
        );

        report.setStatus(ReportStatus.COMPLETED);
        report.setCompletedAt(Instant.now());
        report.setPayload(payload);
        reportRepository.save(report);

        log.info("Report COMPLETED reportId={} tenantId={}", report.getId(), msg.tenantId());
    }
}
