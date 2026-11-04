package com.travelguide.harness;

import java.time.Instant;

public record HarnessEvent(
        String runId,
        String workflowId,
        RunState state,
        String stepId,
        int attempt,
        String message,
        Instant occurredAt
) {
}
