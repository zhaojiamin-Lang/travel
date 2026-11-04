package com.travelguide.harness;

import com.travelguide.harness.resilience.Resilience4jStepExecutor;
import com.travelguide.harness.resilience.StepExecution;
import com.travelguide.harness.store.CheckpointStore;
import com.travelguide.harness.store.IdempotencyStore;
import com.travelguide.harness.store.IdempotencyStore.Decision;
import com.travelguide.harness.store.IdempotencyStore.IdempotencyClaim;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 有状态、可恢复的工作流协调器。
 *
 * <p>检查点决定下一步应执行什么。步骤动作必须遵守传入的幂等键，
 * 这样即使副作用与检查点写入之间发生崩溃，也可以安全重放。</p>
 */
public final class AgentHarness implements AutoCloseable {
    private final CheckpointStore checkpointStore;
    private final IdempotencyStore idempotencyStore;
    private final ContextCompactor contextCompactor;
    private final Resilience4jStepExecutor stepExecutor;
    private final HarnessPolicy policy;
    private final HarnessEventListener eventListener;
    private final Clock clock;
    private final boolean ownsStepExecutor;
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();
    private final Set<String> interruptRequests = ConcurrentHashMap.newKeySet();

    public AgentHarness(
            CheckpointStore checkpointStore,
            IdempotencyStore idempotencyStore,
            HarnessPolicy policy
    ) {
        this(
                checkpointStore,
                idempotencyStore,
                new ContextWindowCompactor(),
                policy,
                HarnessEventListener.NOOP,
                Clock.systemUTC(),
                new Resilience4jStepExecutor(policy),
                true
        );
    }

    public AgentHarness(
            CheckpointStore checkpointStore,
            IdempotencyStore idempotencyStore,
            ContextCompactor contextCompactor,
            HarnessPolicy policy,
            HarnessEventListener eventListener,
            Clock clock
    ) {
        this(
                checkpointStore,
                idempotencyStore,
                contextCompactor,
                policy,
                eventListener,
                clock,
                new Resilience4jStepExecutor(policy),
                true
        );
    }

    public AgentHarness(
            CheckpointStore checkpointStore,
            IdempotencyStore idempotencyStore,
            ContextCompactor contextCompactor,
            HarnessPolicy policy,
            HarnessEventListener eventListener,
            Clock clock,
            Resilience4jStepExecutor stepExecutor,
            boolean ownsStepExecutor
    ) {
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore,
                "checkpointStore"
        );
        this.idempotencyStore = Objects.requireNonNull(
                idempotencyStore,
                "idempotencyStore"
        );
        this.contextCompactor = Objects.requireNonNull(
                contextCompactor,
                "contextCompactor"
        );
        this.policy = Objects.requireNonNull(policy, "policy");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor");
        this.ownsStepExecutor = ownsStepExecutor;
    }

    public HarnessResult execute(HarnessRunRequest request) {
        Objects.requireNonNull(request, "request");
        if (checkpointStore.find(request.runId()).isPresent()) {
            throw new HarnessConflictException(
                    "run already exists: " + request.runId()
            );
        }
        enterRun(request.runId());
        try {
            Instant now = clock.instant();
            RunCheckpoint checkpoint = new RunCheckpoint(
                    request.runId(),
                    request.workflow().workflowId(),
                    request.workflow().version(),
                    RunState.CREATED,
                    0,
                    request.initialContext(),
                    List.of(),
                    null,
                    null,
                    false,
                    1,
                    now,
                    now.plus(policy.workflowTimeout()),
                    now
            );
            save(checkpoint);
            return drive(request.workflow(), checkpoint);
        } finally {
            activeRuns.remove(request.runId());
        }
    }

    public HarnessResult resume(WorkflowDefinition workflow, String runId) {
        Objects.requireNonNull(workflow, "workflow");
        enterRun(runId);
        try {
            RunCheckpoint checkpoint = load(runId);
            assertWorkflowMatches(workflow, checkpoint);
            if (checkpoint.state() == RunState.SUCCEEDED) {
                return checkpoint.toResult();
            }
            if (checkpoint.state() == RunState.FAILED) {
                throw new HarnessConflictException(
                        "failed run is terminal; create a new run instead"
                );
            }
            //主循环干四件事：查中断、查超时、查背包、干下一步
            if (checkpoint.nextStepIndex() > workflow.steps().size()) {
                throw new HarnessConflictException(
                        "checkpoint points beyond the workflow definition"
                );
            }
            interruptRequests.remove(runId);
            RunCheckpoint ready = transition(
                    checkpoint,
                    RunState.READY,
                    checkpoint.nextStepIndex(),
                    checkpoint.context(),
                    checkpoint.completedSteps(),
                    null,
                    null,
                    false
            );
            save(ready);
            return drive(workflow, ready);
        } finally {
            activeRuns.remove(runId);
        }
    }

    public RunCheckpoint checkpoint(String runId) {
        return load(runId);
    }

    /**
     * 协作式中断。正在执行的步骤可以正常完成或触发超时，
     * 随后工作流会在安全边界提交并暂停。
     */
    public void requestInterrupt(String runId) {
        interruptRequests.add(runId);
        if (activeRuns.contains(runId)) {
            return;
        }
        checkpointStore.find(runId)
                .filter(checkpoint -> checkpoint.state() != RunState.SUCCEEDED
                        && checkpoint.state() != RunState.FAILED)
                .ifPresent(checkpoint -> {
                    RunCheckpoint suspended = transition(
                            checkpoint,
                            RunState.SUSPENDED,
                            checkpoint.nextStepIndex(),
                            checkpoint.context(),
                            checkpoint.completedSteps(),
                            "USER_INTERRUPTED",
                            "workflow was interrupted before the next step",
                            true
                    );
                    save(suspended);
                    emit(suspended, null, 0, "interrupted outside an active call");
                });
    }

    @Override
    public void close() {
        if (ownsStepExecutor) {
            stepExecutor.close();
        }
    }

    private HarnessResult drive(
            WorkflowDefinition workflow,
            RunCheckpoint initial
    ) {
        RunCheckpoint checkpoint = initial;
        emitState(checkpoint, RunState.RESTORING, null, 0, "restoring workflow checkpoint");
        emitState(checkpoint, RunState.READY, null, 0, "workflow ready");

        while (true) {
            if (interrupted(checkpoint.runId())) {
                return suspend(
                        checkpoint,
                        "USER_INTERRUPTED",
                        "workflow interrupted at a safe boundary"
                );
            }
            if (!clock.instant().isBefore(checkpoint.deadlineAt())) {
                return suspend(
                        checkpoint,
                        "WORKFLOW_TIMEOUT",
                        "workflow deadline exceeded"
                );
            }
            if (checkpoint.nextStepIndex() >= workflow.steps().size()) {
                return succeed(checkpoint);
            }

            ContextCompactor.CompactionResult compaction =
                    contextCompactor.compact(
                            checkpoint.context(),
                            policy.maxContextTokens()
                    );
            if (compaction.overBudget()) {
                return suspend(
                        checkpoint,
                        "CONTEXT_BUDGET_EXCEEDED",
                        "preserved context exceeds the configured token budget"
                );
            }
            if (compaction.compacted()) {
                emitState(
                        checkpoint,
                        RunState.COMPACTING,
                        null,
                        0,
                        "compacting narrative context"
                );
                checkpoint = transition(
                        checkpoint,
                        RunState.CHECKPOINTED,
                        checkpoint.nextStepIndex(),
                        compaction.context(),
                        checkpoint.completedSteps(),
                        null,
                        null,
                        false
                );
                save(checkpoint);
                emitState(
                        checkpoint,
                        RunState.CHECKPOINTED,
                        null,
                        0,
                        "compacted context checkpointed"
                );
            }

            WorkflowStep step = workflow.steps().get(checkpoint.nextStepIndex());
            StepOutcome outcome = executeStep(workflow, checkpoint, step);
            checkpoint = outcome.checkpoint();
            if (outcome.terminalResult() != null) {
                return outcome.terminalResult();
            }
        }
    }
    //取号、干活、三道保险、提交
    private StepOutcome executeStep(
            WorkflowDefinition workflow,
            RunCheckpoint checkpoint,
            WorkflowStep step
    ) {
        String idempotencyKey = idempotencyKey(workflow, checkpoint, step);
        String fingerprint = fingerprint(workflow, step);
        IdempotencyClaim claim = idempotencyStore.begin(
                idempotencyKey,
                fingerprint,
                policy.idempotencyLease()
        );

        if (claim.decision() == Decision.COMPLETED) {
            StepResult result = claim.result().orElseThrow();
            emit(checkpoint, step.stepId(), 0, "replayed committed step result");
            return checkpointResult(
                    checkpoint,
                    step,
                    result,
                    0,
                    false,
                    Map.of("idempotencyReplay", "true")
            );
        }
        if (claim.decision() == Decision.BUSY) {
            return terminal(suspend(
                    checkpoint,
                    "IDEMPOTENCY_IN_PROGRESS",
                    "another worker owns this step; retry after "
                            + claim.retryAfter()
            ));
        }
        if (claim.decision() == Decision.FINGERPRINT_CONFLICT) {
            return terminal(fail(
                    checkpoint,
                    "IDEMPOTENCY_CONFLICT",
                    "idempotency key was reused with a different request"
            ));
        }

        RunCheckpoint running = transition(
                checkpoint,
                RunState.GUARDING,
                checkpoint.nextStepIndex(),
                checkpoint.context(),
                checkpoint.completedSteps(),
                null,
                null,
                interrupted(checkpoint.runId())
        );
        save(running);
        emitState(
                running,
                RunState.GUARDING,
                step.stepId(),
                0,
                "checking circuit breaker"
        );
        if (interrupted(running.runId())) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            return terminal(suspend(
                    running,
                    "USER_INTERRUPTED",
                    "workflow interrupted before starting the claimed step"
            ));
        }

        StepExecution execution;
        try {
            execution = stepExecutor.execute(
                    scope(workflow, step),
                    step.policy(),
                    attempt -> step.action().execute(new StepContext(
                            checkpoint.runId(),
                            workflow.workflowId(),
                            step.stepId(),
                            idempotencyKey,
                            attempt,
                            running.context()
                    )),
                    attempt -> {
                        if (attempt > 1) {
                            emitState(
                                    running,
                                    RunState.BACKOFF,
                                    step.stepId(),
                                    attempt,
                                    "backoff elapsed; starting retry"
                            );
                        }
                        emitState(
                                running,
                                RunState.EXECUTING,
                                step.stepId(),
                                attempt,
                                "executing step"
                        );
                    }
            );
        } catch (CallNotPermittedException exception) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            if (step.fallback() != null) {
                emitState(
                        running,
                        RunState.GUARDING,
                        step.stepId(),
                        0,
                        "circuit open; invoking fallback"
                );
                return executeFallback(
                        workflow,
                        running,
                        step,
                        idempotencyKey,
                        fingerprint,
                        exception
                );
            }
            return terminal(suspend(
                    running,
                    "CIRCUIT_OPEN",
                    "circuit is open and no fallback is configured"
            ));
        } catch (TimeoutException exception) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            return terminal(suspend(
                    running,
                    "STEP_TIMEOUT",
                    "step exceeded its per-attempt timeout"
            ));
        } catch (RetryableStepException exception) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            return terminal(suspend(
                    running,
                    exception.code(),
                    exception.getMessage()
            ));
        } catch (StepRejectedException exception) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            return terminal(fail(
                    running,
                    exception.code(),
                    exception.getMessage()
            ));
        } catch (Exception exception) {
            idempotencyStore.release(idempotencyKey, claim.claimToken());
            return terminal(fail(
                    running,
                    "STEP_EXECUTION_FAILED",
                    messageOf(exception)
            ));
        }
        return commitStep(
                running,
                step,
                idempotencyKey,
                claim.claimToken(),
                execution.result(),
                execution.attempts(),
                false,
                Map.of()
        );
    }

    private StepOutcome executeFallback(
            WorkflowDefinition workflow,
            RunCheckpoint checkpoint,
            WorkflowStep step,
            String idempotencyKey,
            String fingerprint,
            Exception cause
    ) {
        IdempotencyClaim fallbackClaim = idempotencyStore.begin(
                idempotencyKey,
                fingerprint,
                policy.idempotencyLease()
        );
        if (fallbackClaim.decision() == Decision.COMPLETED) {
            return checkpointResult(
                    checkpoint,
                    step,
                    fallbackClaim.result().orElseThrow(),
                    0,
                    true,
                    Map.of("idempotencyReplay", "true")
            );
        }
        if (fallbackClaim.decision() == Decision.BUSY) {
            return terminal(suspend(
                    checkpoint,
                    "IDEMPOTENCY_IN_PROGRESS",
                    "fallback step is owned by another worker"
            ));
        }
        if (fallbackClaim.decision() == Decision.FINGERPRINT_CONFLICT) {
            return terminal(fail(
                    checkpoint,
                    "IDEMPOTENCY_CONFLICT",
                    "fallback reused an incompatible idempotency key"
            ));
        }

        StepExecution execution;
        try {
            execution = stepExecutor.executeFallback(
                    scope(workflow, step),
                    attempt -> step.fallback().execute(new StepContext(
                            checkpoint.runId(),
                            workflow.workflowId(),
                            step.stepId(),
                            idempotencyKey,
                            attempt,
                            checkpoint.context()
                    ), cause),
                    attempt -> emitState(
                            checkpoint,
                            RunState.EXECUTING,
                            step.stepId(),
                            attempt,
                            "executing degradation path"
                    )
            );
        } catch (Exception exception) {
            idempotencyStore.release(idempotencyKey, fallbackClaim.claimToken());
            return terminal(suspend(
                    checkpoint,
                    "FALLBACK_FAILED",
                    messageOf(exception)
            ));
        }
        return commitStep(
                checkpoint,
                step,
                idempotencyKey,
                fallbackClaim.claimToken(),
                execution.result(),
                execution.attempts(),
                true,
                Map.of("fallbackReason", "CIRCUIT_OPEN")
        );
    }

    private StepOutcome commitStep(
            RunCheckpoint checkpoint,
            WorkflowStep step,
            String idempotencyKey,
            String claimToken,
            StepResult result,
            int attempts,
            boolean degraded,
            Map<String, String> extraMetadata
    ) {
        emitState(
                checkpoint,
                RunState.COMMITTING,
                step.stepId(),
                attempts,
                "committing step result"
        );
        idempotencyStore.complete(idempotencyKey, claimToken, result);
        return checkpointResult(
                checkpoint,
                step,
                result,
                attempts,
                degraded,
                extraMetadata
        );
    }

    private StepOutcome checkpointResult(
            RunCheckpoint checkpoint,
            WorkflowStep step,
            StepResult result,
            int attempts,
            boolean degraded,
            Map<String, String> extraMetadata
    ) {
        Map<String, String> metadata = new LinkedHashMap<>(result.metadata());
        metadata.putAll(extraMetadata);
        StepRecord record = new StepRecord(
                step.stepId(),
                result.output(),
                metadata,
                attempts,
                degraded,
                clock.instant()
        );
        List<StepRecord> completed = new ArrayList<>(checkpoint.completedSteps());
        completed.add(record);
        List<ContextItem> context = new ArrayList<>(checkpoint.context());
        context.addAll(result.contextAdditions());
        int nextIndex = checkpoint.nextStepIndex() + 1;
        RunCheckpoint next = transition(
                checkpoint,
                RunState.CHECKPOINTED,
                nextIndex,
                context,
                completed,
                null,
                null,
                interrupted(checkpoint.runId())
        );
        save(next);
        emitState(
                next,
                RunState.CHECKPOINTED,
                step.stepId(),
                attempts,
                "step checkpoint committed"
        );
        return new StepOutcome(next, null);
    }

    private StepOutcome terminal(HarnessResult result) {
        return new StepOutcome(null, result);
    }

    private HarnessResult succeed(RunCheckpoint checkpoint) {
        RunCheckpoint succeeded = transition(
                checkpoint,
                RunState.SUCCEEDED,
                checkpoint.nextStepIndex(),
                checkpoint.context(),
                checkpoint.completedSteps(),
                null,
                null,
                false
        );
        save(succeeded);
        emit(succeeded, null, 0, "workflow completed");
        interruptRequests.remove(checkpoint.runId());
        return succeeded.toResult();
    }

    private HarnessResult suspend(
            RunCheckpoint checkpoint,
            String code,
            String message
    ) {
        RunCheckpoint suspended = transition(
                checkpoint,
                RunState.SUSPENDED,
                checkpoint.nextStepIndex(),
                checkpoint.context(),
                checkpoint.completedSteps(),
                code,
                message,
                true
        );
        save(suspended);
        emit(suspended, null, 0, "workflow suspended: " + code);
        Thread.interrupted();
        return suspended.toResult();
    }

    private HarnessResult fail(
            RunCheckpoint checkpoint,
            String code,
            String message
    ) {
        RunCheckpoint failed = transition(
                checkpoint,
                RunState.FAILED,
                checkpoint.nextStepIndex(),
                checkpoint.context(),
                checkpoint.completedSteps(),
                code,
                message,
                false
        );
        save(failed);
        emit(failed, null, 0, "workflow failed: " + code);
        interruptRequests.remove(checkpoint.runId());
        return failed.toResult();
    }

    private RunCheckpoint transition(
            RunCheckpoint current,
            RunState state,
            int nextStepIndex,
            List<ContextItem> context,
            List<StepRecord> completed,
            String errorCode,
            String errorMessage,
            boolean interruptRequested
    ) {
        return new RunCheckpoint(
                current.runId(),
                current.workflowId(),
                current.workflowVersion(),
                state,
                nextStepIndex,
                context,
                completed,
                errorCode,
                errorMessage,
                interruptRequested,
                current.version() + 1,
                current.startedAt(),
                current.deadlineAt(),
                clock.instant()
        );
    }

    private void enterRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (!activeRuns.add(runId)) {
            throw new HarnessConflictException(
                    "run already has an active worker: " + runId
            );
        }
    }

    private boolean interrupted(String runId) {
        return interruptRequests.contains(runId)
                || Thread.currentThread().isInterrupted();
    }

    private RunCheckpoint load(String runId) {
        return checkpointStore.find(runId)
                .orElseThrow(() -> new HarnessConflictException(
                        "checkpoint not found: " + runId
                ));
    }

    private void save(RunCheckpoint checkpoint) {
        checkpointStore.save(checkpoint);
    }

    private void assertWorkflowMatches(
            WorkflowDefinition workflow,
            RunCheckpoint checkpoint
    ) {
        if (!workflow.workflowId().equals(checkpoint.workflowId())
                || !workflow.version().equals(checkpoint.workflowVersion())) {
            throw new HarnessConflictException(
                    "workflow definition does not match checkpoint"
            );
        }
    }

    private String idempotencyKey(
            WorkflowDefinition workflow,
            RunCheckpoint checkpoint,
            WorkflowStep step
    ) {
        return workflow.workflowId()
                + ":" + workflow.version()
                + ":" + checkpoint.runId()
                + ":" + step.stepId();
    }

    private String fingerprint(
            WorkflowDefinition workflow,
            WorkflowStep step
    ) {
        return workflow.workflowId()
                + ":" + workflow.version()
                + ":" + step.stepId();
    }

    private String scope(
            WorkflowDefinition workflow,
            WorkflowStep step
    ) {
        return workflow.workflowId()
                + ":" + workflow.version()
                + ":" + step.stepId();
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private void emit(
            RunCheckpoint checkpoint,
            String stepId,
            int attempt,
            String message
    ) {
        emitState(checkpoint, checkpoint.state(), stepId, attempt, message);
    }

    private void emitState(
            RunCheckpoint checkpoint,
            RunState state,
            String stepId,
            int attempt,
            String message
    ) {
        try {
            eventListener.onEvent(new HarnessEvent(
                    checkpoint.runId(),
                    checkpoint.workflowId(),
                    state,
                    stepId,
                    attempt,
                    message,
                    clock.instant()
            ));
        } catch (RuntimeException ignored) {
            // 可观测性处理不能影响工作流执行。
        }
    }

    private record StepOutcome(
            RunCheckpoint checkpoint,
            HarnessResult terminalResult
    ) {
    }
}
