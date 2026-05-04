package com.workhub.backend.messaging;

import java.util.UUID;

public record ReportJobMessage(
        UUID messageId,
        UUID tenantId,
        UUID projectId,
        UUID reportId,
        UUID requestedBy
) {
}
