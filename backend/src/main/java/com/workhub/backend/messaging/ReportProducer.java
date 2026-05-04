package com.workhub.backend.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import static com.workhub.backend.messaging.KafkaTopics.REPORTS_GENERATE;

@Component
public class ReportProducer {

    private static final Logger log = LoggerFactory.getLogger(ReportProducer.class);

    @SuppressWarnings("rawtypes")
    private final KafkaTemplate kafkaTemplate;

    public ReportProducer(@SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a report job. Key = tenantId so per-tenant ordering is preserved.
     */
    @SuppressWarnings("unchecked")
    public void publish(ReportJobMessage message) {
        log.info("Publishing report job messageId={} tenantId={} reportId={}",
                message.messageId(), message.tenantId(), message.reportId());
        kafkaTemplate.send(REPORTS_GENERATE, message.tenantId().toString(), message);
    }
}
