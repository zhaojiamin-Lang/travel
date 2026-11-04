package com.travelguide.harness.store;

import com.travelguide.harness.StepResult;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {
    IdempotencyClaim begin(
            String key,
            String requestFingerprint,
            Duration leaseDuration
    );

    void complete(String key, String claimToken, StepResult result);

    void release(String key, String claimToken);

    record IdempotencyClaim(
            Decision decision,
            String claimToken,
            Duration retryAfter,
            StepResult completedResult
    ) {
        public static IdempotencyClaim claimed(String token) {
            return new IdempotencyClaim(Decision.CLAIMED, token, null, null);
        }

        public static IdempotencyClaim completed(StepResult result) {
            return new IdempotencyClaim(Decision.COMPLETED, null, null, result);
        }

        public static IdempotencyClaim busy(Duration retryAfter) {
            return new IdempotencyClaim(Decision.BUSY, null, retryAfter, null);
        }

        public static IdempotencyClaim conflict() {
            return new IdempotencyClaim(Decision.FINGERPRINT_CONFLICT, null, null, null);
        }

        public Optional<StepResult> result() {
            return Optional.ofNullable(completedResult);
        }
    }

    enum Decision {
        CLAIMED,
        COMPLETED,
        BUSY,
        FINGERPRINT_CONFLICT
    }
}
