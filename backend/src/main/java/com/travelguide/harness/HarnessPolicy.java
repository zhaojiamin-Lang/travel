package com.travelguide.harness;

import java.time.Duration;

public record HarnessPolicy(
        int maxContextTokens,
        Duration timeoutPerAttempt,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        int slidingWindowSize,
        int minimumCalls,
        float failureRateThreshold,
        Duration circuitOpenWait,
        Duration workflowTimeout,
        Duration idempotencyLease
) {
    public HarnessPolicy {
        if (maxContextTokens < 1) {
            throw new IllegalArgumentException("maxContextTokens must be positive");
        }
        requirePositive(timeoutPerAttempt, "timeoutPerAttempt");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        requireNonNegative(initialBackoff, "initialBackoff");
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("slidingWindowSize must be positive");
        }
        if (minimumCalls < 1 || minimumCalls > slidingWindowSize) {
            throw new IllegalArgumentException(
                    "minimumCalls must be between 1 and slidingWindowSize"
            );
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException(
                    "failureRateThreshold must be in (0, 100]"
            );
        }
        requirePositive(circuitOpenWait, "circuitOpenWait");
        requirePositive(workflowTimeout, "workflowTimeout");
        requirePositive(idempotencyLease, "idempotencyLease");
    }

    public static HarnessPolicy defaults() {
        return new HarnessPolicy(
                24_000,
                Duration.ofSeconds(10),
                3,
                Duration.ofMillis(200),
                2.0,
                10,
                5,
                50.0f,
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofMinutes(10)
        );
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
