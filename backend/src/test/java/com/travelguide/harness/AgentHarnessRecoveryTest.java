package com.travelguide.harness;

import com.travelguide.harness.store.CheckpointStore;
import com.travelguide.harness.store.InMemoryCheckpointStore;
import com.travelguide.harness.store.InMemoryIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHarnessRecoveryTest {
    @Test
    void resumesAfterCooperativeInterruptWithoutRepeatingCompletedSteps()
            throws Exception {
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
        CountDownLatch stepTwoStarted = new CountDownLatch(1);
        CountDownLatch releaseStepTwo = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger thirdCalls = new AtomicInteger();

        WorkflowDefinition workflow = new WorkflowDefinition(
                "trip-plan",
                "v1",
                List.of(
                        new WorkflowStep("first", context -> {
                            firstCalls.incrementAndGet();
                            return StepResult.of("first-result");
                        }, ExecutionPolicy.once()),
                        new WorkflowStep("second", context -> {
                            secondCalls.incrementAndGet();
                            stepTwoStarted.countDown();
                            assertThat(releaseStepTwo.await(2, TimeUnit.SECONDS)).isTrue();
                            return StepResult.of("second-result");
                        }, ExecutionPolicy.once()),
                        new WorkflowStep("third", context -> {
                            thirdCalls.incrementAndGet();
                            return StepResult.of("third-result");
                        }, ExecutionPolicy.once())
                )
        );
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (AgentHarness harness = new AgentHarness(
                checkpoints,
                idempotency,
                policy(1)
        )) {
            Future<HarnessResult> running = caller.submit(() -> harness.execute(
                    new HarnessRunRequest("run-interrupt", workflow, List.of())
            ));
            assertThat(stepTwoStarted.await(2, TimeUnit.SECONDS)).isTrue();

            harness.requestInterrupt("run-interrupt");
            releaseStepTwo.countDown();
            HarnessResult interrupted = running.get(3, TimeUnit.SECONDS);

            assertThat(interrupted.state()).isEqualTo(RunState.SUSPENDED);
            assertThat(interrupted.failure().code()).isEqualTo("USER_INTERRUPTED");
            assertThat(interrupted.completedSteps())
                    .extracting(StepRecord::stepId)
                    .containsExactly("first", "second");
            assertThat(thirdCalls).hasValue(0);

            HarnessResult resumed = harness.resume(workflow, "run-interrupt");

            assertThat(resumed.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(firstCalls).hasValue(1);
            assertThat(secondCalls).hasValue(1);
            assertThat(thirdCalls).hasValue(1);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void replaysCommittedIdempotencyResultWhenCheckpointWriteWasLost()
            throws Exception {
        InMemoryCheckpointStore delegate = new InMemoryCheckpointStore();
        FailingCheckpointStore checkpoints = new FailingCheckpointStore(delegate);
        InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
        AtomicInteger sideEffects = new AtomicInteger();
        WorkflowDefinition workflow = new WorkflowDefinition(
                "booking",
                "v1",
                List.of(
                        new WorkflowStep("create-booking", context -> {
                            sideEffects.incrementAndGet();
                            return StepResult.of("booking-42");
                        }, ExecutionPolicy.once()),
                        new WorkflowStep(
                                "confirm",
                                context -> StepResult.of("confirmed"),
                                ExecutionPolicy.once()
                        )
                )
        );

        try (AgentHarness first = new AgentHarness(
                checkpoints,
                idempotency,
                policy(1)
        )) {
            assertThatThrownBy(() -> first.execute(
                    new HarnessRunRequest("run-write", workflow, List.of())
            )).isInstanceOf(IllegalStateException.class);
        }
        assertThat(sideEffects).hasValue(1);
        assertThat(delegate.find("run-write").orElseThrow().state())
                .isEqualTo(RunState.GUARDING);

        try (AgentHarness second = new AgentHarness(
                checkpoints,
                idempotency,
                policy(1)
        )) {
            HarnessResult result = second.resume(workflow, "run-write");

            assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(result.completedSteps()).hasSize(2);
            assertThat(result.completedSteps().get(0).metadata())
                    .containsEntry("idempotencyReplay", "true");
            assertThat(sideEffects).hasValue(1);
        }
    }

    static HarnessPolicy policy(int maxAttempts) {
        return new HarnessPolicy(
                8_000,
                Duration.ofSeconds(2),
                maxAttempts,
                Duration.ofMillis(1),
                2.0,
                4,
                2,
                50.0f,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2)
        );
    }

    private static final class FailingCheckpointStore implements CheckpointStore {
        private final CheckpointStore delegate;
        private final AtomicBoolean fail = new AtomicBoolean(true);

        private FailingCheckpointStore(CheckpointStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(RunCheckpoint checkpoint) {
            if (checkpoint.state() == RunState.CHECKPOINTED
                    && fail.compareAndSet(true, false)) {
                throw new IllegalStateException("injected checkpoint failure");
            }
            delegate.save(checkpoint);
        }

        @Override
        public java.util.Optional<RunCheckpoint> find(String runId) {
            return delegate.find(runId);
        }
    }
}
