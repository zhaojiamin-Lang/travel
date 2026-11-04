package com.travelguide.harness;

import com.travelguide.harness.store.InMemoryCheckpointStore;
import com.travelguide.harness.store.InMemoryIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHarnessResilienceTest {
    @Test
    void emitsExplicitStateMachineTransitions() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        WorkflowDefinition workflow = new WorkflowDefinition(
                "state-flow",
                "v1",
                List.of(new WorkflowStep(
                        "single",
                        context -> StepResult.of("ok"),
                        ExecutionPolicy.once()
                ))
        );

        try (AgentHarness harness = new AgentHarness(
                new InMemoryCheckpointStore(),
                new InMemoryIdempotencyStore(),
                new ContextWindowCompactor(),
                policy(1, Duration.ofSeconds(2)),
                events::add,
                Clock.systemUTC()
        )) {
            HarnessResult result = harness.execute(
                    new HarnessRunRequest("run-events", workflow, List.of())
            );

            assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(events)
                    .extracting(HarnessEvent::state)
                    .containsSubsequence(
                            RunState.RESTORING,
                            RunState.READY,
                            RunState.GUARDING,
                            RunState.EXECUTING,
                            RunState.COMMITTING,
                            RunState.CHECKPOINTED,
                            RunState.SUCCEEDED
                    );
        }
    }

    @Test
    void retriesOnlyExplicitlyRetryableFailuresWithBackoff() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition workflow = new WorkflowDefinition(
                "weather",
                "v1",
                List.of(new WorkflowStep("lookup", context -> {
                    int attempt = calls.incrementAndGet();
                    if (attempt < 3) {
                        throw new RetryableStepException(
                                "WEATHER_TEMPORARY",
                                "temporary weather provider failure"
                        );
                    }
                    return new StepResult(
                            "sunny",
                            java.util.Map.of("attempt", Integer.toString(context.attempt())),
                            List.of()
                    );
                }))
        );

        try (AgentHarness harness = new AgentHarness(
                new InMemoryCheckpointStore(),
                new InMemoryIdempotencyStore(),
                policy(3, Duration.ofSeconds(2))
        )) {
            HarnessResult result = harness.execute(
                    new HarnessRunRequest("run-retry", workflow, List.of())
            );

            assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(calls).hasValue(3);
            assertThat(result.completedSteps().get(0).attempts()).isEqualTo(3);
            assertThat(result.completedSteps().get(0).output()).isEqualTo("sunny");
        }
    }

    @Test
    void suspendsWhenStepExceedsPerAttemptTimeout() {
        WorkflowDefinition workflow = new WorkflowDefinition(
                "slow-tool",
                "v1",
                List.of(new WorkflowStep(
                        "slow",
                        context -> {
                            Thread.sleep(500);
                            return StepResult.of("too-late");
                        },
                        ExecutionPolicy.once()
                ))
        );

        long startedAt = System.nanoTime();
        HarnessResult result;
        try (AgentHarness harness = new AgentHarness(
                new InMemoryCheckpointStore(),
                new InMemoryIdempotencyStore(),
                policy(1, Duration.ofMillis(50))
        )) {
            result = harness.execute(
                    new HarnessRunRequest("run-timeout", workflow, List.of())
            );
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(result.state()).isEqualTo(RunState.SUSPENDED);
        assertThat(result.failure().code()).isEqualTo("STEP_TIMEOUT");
        assertThat(elapsedMs).isLessThan(1_000);
    }

    @Test
    void opensCircuitAndUsesConfiguredDegradationPath() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        WorkflowStep step = new WorkflowStep(
                "inventory",
                context -> {
                    primaryCalls.incrementAndGet();
                    throw new RetryableStepException(
                            "INVENTORY_UNAVAILABLE",
                            "inventory service unavailable"
                    );
                },
                ExecutionPolicy.once()
        ).withFallback((context, cause) -> {
            fallbackCalls.incrementAndGet();
            return StepResult.of("check availability manually");
        });
        WorkflowDefinition workflow = new WorkflowDefinition(
                "inventory-check",
                "v1",
                List.of(step)
        );

        try (AgentHarness harness = new AgentHarness(
                new InMemoryCheckpointStore(),
                new InMemoryIdempotencyStore(),
                policy(1, Duration.ofSeconds(2))
        )) {
            HarnessResult first = harness.execute(new HarnessRunRequest(
                    "run-circuit-1",
                    workflow,
                    List.of()
            ));
            HarnessResult second = harness.execute(new HarnessRunRequest(
                    "run-circuit-2",
                    workflow,
                    List.of()
            ));
            HarnessResult third = harness.execute(new HarnessRunRequest(
                    "run-circuit-3",
                    workflow,
                    List.of()
            ));

            assertThat(first.state()).isEqualTo(RunState.SUSPENDED);
            assertThat(second.state()).isEqualTo(RunState.SUSPENDED);
            assertThat(third.state()).isEqualTo(RunState.SUCCEEDED);
            assertThat(primaryCalls).hasValue(2);
            assertThat(fallbackCalls).hasValue(1);
            assertThat(third.completedSteps().get(0).degraded()).isTrue();
            assertThat(third.completedSteps().get(0).metadata())
                    .containsEntry("fallbackReason", "CIRCUIT_OPEN");
        }
    }

    private HarnessPolicy policy(int maxAttempts, Duration timeout) {
        return new HarnessPolicy(
                8_000,
                timeout,
                maxAttempts,
                Duration.ofMillis(1),
                2.0,
                2,
                2,
                50.0f,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2)
        );
    }
}
