package com.travelguide.harness.store;

import com.travelguide.harness.RunCheckpoint;

import java.util.Optional;

public interface CheckpointStore {
    void save(RunCheckpoint checkpoint);

    Optional<RunCheckpoint> find(String runId);
}
