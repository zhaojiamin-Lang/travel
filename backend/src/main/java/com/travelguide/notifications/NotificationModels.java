package com.travelguide.notifications;

import java.time.Instant;

public final class NotificationModels {

    private NotificationModels() {
    }

    public record ProposalNotification(
            String proposalId,
            String planId,
            String eventType,
            String summary,
            String status,
            Instant createdAt
    ) {
    }
}
