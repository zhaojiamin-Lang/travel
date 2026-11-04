package com.travelguide.harness.store;

import com.travelguide.harness.RunCheckpoint;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryCheckpointStore implements CheckpointStore {
    private final ConcurrentMap<String, RunCheckpoint> checkpoints =
            new ConcurrentHashMap<>();

    @Override
    public void save(RunCheckpoint checkpoint) {
        checkpoints.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public Optional<RunCheckpoint> find(String runId) {
        return Optional.ofNullable(checkpoints.get(runId));
    }
}
