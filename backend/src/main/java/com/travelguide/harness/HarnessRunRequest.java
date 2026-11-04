package com.travelguide.harness;

import java.util.List;
import java.util.Objects;

public record HarnessRunRequest(
        String runId,
        WorkflowDefinition workflow,
        List<ContextItem> initialContext
) {
    public HarnessRunRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        workflow = Objects.requireNonNull(workflow, "workflow");
        initialContext = List.copyOf(
                Objects.requireNonNull(initialContext, "initialContext")
        );
    }
}
