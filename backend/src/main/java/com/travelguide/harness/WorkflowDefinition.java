package com.travelguide.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record WorkflowDefinition(
        String workflowId,
        String version,
        List<WorkflowStep> steps
) {
    public WorkflowDefinition {
        workflowId = requireText(workflowId, "workflowId");
        version = requireText(version, "version");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("workflow must contain at least one step");
        }
        Set<String> ids = new HashSet<>();
        for (WorkflowStep step : steps) {
            if (!ids.add(step.stepId())) {
                throw new IllegalArgumentException("duplicate step id: " + step.stepId());
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
