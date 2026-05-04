package com.workhub.backend.messaging;

import com.workhub.backend.entity.Report;
import com.workhub.backend.entity.ReportStatus;
import com.workhub.backend.repository.ReportRepository;
import com.workhub.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.workhub.backend.messaging.KafkaTopics.REPORTS_GENERATE_DLT;

@Component
public class ReportDltListener {

    private static final Logger log = LoggerFactory.getLogger(ReportDltListener.class);

    private final ReportRepository reportRepository;

    public ReportDltListener(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @KafkaListener(topics = REPORTS_GENERATE_DLT, groupId = "${spring.kafka.consumer.group-id}-dlt")
    @Transactional
    public void onDeadLetter(ReportJobMessage msg) {
        log.warn("DLT received messageId={} tenantId={} reportId={}",
                msg.messageId(), msg.tenantId(), msg.reportId());
        TenantContext.setTenantId(msg.tenantId());
        try {
            reportRepository.findByIdAndTenant_Id(msg.reportId(), msg.tenantId())
                    .ifPresent(this::markFailed);
        } finally {
            TenantContext.clear();
        }
    }

    private void markFailed(Report report) {
        report.setStatus(ReportStatus.FAILED);
        report.setCompletedAt(Instant.now());
        report.setPayload("Report generation failed after retries (moved to DLT).");
        reportRepository.save(report);
    }
}
