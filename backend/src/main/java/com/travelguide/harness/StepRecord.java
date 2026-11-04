package com.travelguide.harness;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record StepRecord(
        String stepId,
        String output,
        Map<String, String> metadata,
        int attempts,
        boolean degraded,
        Instant completedAt
) {
    public StepRecord {
        stepId = requireText(stepId, "stepId");
        output = Objects.requireNonNull(output, "output");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
