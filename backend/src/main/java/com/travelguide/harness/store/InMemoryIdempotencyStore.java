package com.travelguide.harness.store;

import com.travelguide.harness.StepResult;
import com.travelguide.harness.store.IdempotencyStore.IdempotencyClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, Record> records = new HashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized IdempotencyClaim begin(
            String key,
            String requestFingerprint,
            Duration leaseDuration
    ) {
        Instant now = clock.instant();
        Record current = records.get(key);
        if (current == null) {
            String token = UUID.randomUUID().toString();
            records.put(key, new Record(
                    requestFingerprint,
                    Status.CLAIMED,
                    token,
                    now.plus(leaseDuration),
                    null
            ));
            return IdempotencyClaim.claimed(token);
        }
        if (!current.fingerprint().equals(requestFingerprint)) {
            return IdempotencyClaim.conflict();
        }
        if (current.status() == Status.COMPLETED) {
            return IdempotencyClaim.completed(current.result());
        }
        if (current.leaseUntil().isAfter(now)) {
            return IdempotencyClaim.busy(
                    Duration.between(now, current.leaseUntil())
            );
        }

        String token = UUID.randomUUID().toString();
        records.put(key, new Record(
                requestFingerprint,
                Status.CLAIMED,
                token,
                now.plus(leaseDuration),
                null
        ));
        return IdempotencyClaim.claimed(token);
    }

    @Override
    public synchronized void complete(
            String key,
            String claimToken,
            StepResult result
    ) {
        Record current = records.get(key);
        if (current == null
                || current.status() != Status.CLAIMED
                || !current.claimToken().equals(claimToken)) {
            throw new IllegalStateException("idempotency claim is not owned");
        }
        records.put(key, new Record(
                current.fingerprint(),
                Status.COMPLETED,
                null,
                null,
                result
        ));
    }

    @Override
    public synchronized void release(String key, String claimToken) {
        Record current = records.get(key);
        if (current != null
                && current.status() == Status.CLAIMED
                && current.claimToken().equals(claimToken)) {
            records.remove(key);
        }
    }

    private enum Status {
        CLAIMED,
        COMPLETED
    }

    private record Record(
            String fingerprint,
            Status status,
            String claimToken,
            Instant leaseUntil,
            StepResult result
    ) {
    }
}
