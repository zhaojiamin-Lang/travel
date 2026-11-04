package com.travelguide.harness;

import java.util.Objects;

public record WorkflowStep(
        String stepId,
        AgentStep action,
        ExecutionPolicy policy,
        StepFallback fallback
) {
    public WorkflowStep {
        stepId = requireText(stepId, "stepId");
        action = Objects.requireNonNull(action, "action");
        policy = Objects.requireNonNull(policy, "policy");
    }

    public WorkflowStep(String stepId, AgentStep action) {
        this(stepId, action, ExecutionPolicy.retryable(), null);
    }

    public WorkflowStep(
            String stepId,
            AgentStep action,
            ExecutionPolicy policy
    ) {
        this(stepId, action, policy, null);
    }

    public WorkflowStep withFallback(StepFallback value) {
        return new WorkflowStep(stepId, action, policy, value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
