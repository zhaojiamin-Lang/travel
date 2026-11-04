package com.travelguide.harness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RunCheckpoint(
        String runId,
        String workflowId,
        String workflowVersion,
        RunState state,
        int nextStepIndex,
        List<ContextItem> context,
        List<StepRecord> completedSteps,
        String lastErrorCode,
        String lastErrorMessage,
        boolean interruptRequested,
        long version,
        Instant startedAt,
        Instant deadlineAt,
        Instant updatedAt
) {
    public RunCheckpoint {
        runId = requireText(runId, "runId");
        workflowId = requireText(workflowId, "workflowId");
        workflowVersion = requireText(workflowVersion, "workflowVersion");
        state = Objects.requireNonNull(state, "state");
        if (nextStepIndex < 0) {
            throw new IllegalArgumentException("nextStepIndex must be non-negative");
        }
        context = List.copyOf(Objects.requireNonNull(context, "context"));
        completedSteps = List.copyOf(
                Objects.requireNonNull(completedSteps, "completedSteps")
        );
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public HarnessResult toResult() {
        HarnessFailure failure = state == RunState.SUSPENDED || state == RunState.FAILED
                ? new HarnessFailure(
                        lastErrorCode,
                        lastErrorMessage,
                        state == RunState.SUSPENDED
                )
                : null;
        return new HarnessResult(
                runId,
                state,
                completedSteps,
                context,
                failure,
                startedAt,
                updatedAt
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
