package com.travelguide.harness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record HarnessResult(
        String runId,
        RunState state,
        List<StepRecord> completedSteps,
        List<ContextItem> context,
        HarnessFailure failure,
        Instant startedAt,
        Instant endedAt
) {
    public HarnessResult {
        completedSteps = List.copyOf(completedSteps);
        context = List.copyOf(context);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
    }

    public boolean succeeded() {
        return state == RunState.SUCCEEDED;
    }
}
