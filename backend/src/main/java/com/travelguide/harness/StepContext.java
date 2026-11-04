package com.travelguide.harness;

import java.util.List;

public record StepContext(
        String runId,
        String workflowId,
        String stepId,
        String idempotencyKey,
        int attempt,
        List<ContextItem> context
) {
    public StepContext {
        context = List.copyOf(context);
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
    }
}
