package com.travelguide.harness.resilience;

import com.travelguide.harness.ExecutionPolicy;
import com.travelguide.harness.HarnessPolicy;
import com.travelguide.harness.RetryableStepException;
import com.travelguide.harness.StepRejectedException;
import com.travelguide.harness.StepResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * 为每次尝试设置独立超时，应用指数退避重试，并按步骤隔离熔断器。
 * 熔断器观察整条重试链的最终结果，因此重试不会虚增熔断失败计数。
 */
public final class Resilience4jStepExecutor implements AutoCloseable {
    private final HarnessPolicy policy;
    private final ExecutorService actionExecutor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Controls> controls = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public Resilience4jStepExecutor(HarnessPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.actionExecutor = Executors.newCachedThreadPool(
                daemonFactory("agent-harness-action-")
        );
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("agent-harness-timeout-")
        );
    }

    public StepExecution execute(
            String scope,
            ExecutionPolicy executionPolicy,
            AttemptSupplier supplier,
            IntConsumer beforeAttempt
    ) throws Exception {
        if (closed) {
            throw new IllegalStateException("step executor is closed");
        }
        Controls stepControls = controls.computeIfAbsent(scope, this::createControls);
        AtomicInteger attempts = new AtomicInteger();
        Callable<StepExecution> timedAttempt = () -> {
            int attempt = attempts.incrementAndGet();
            beforeAttempt.accept(attempt);
            Future<StepResult> future = actionExecutor.submit(
                    () -> supplier.execute(attempt)
            );
            try {
                StepResult result = stepControls.timeLimiter()
                        .executeFutureSupplier(() -> future);
                return new StepExecution(result, attempt);
            } catch (ExecutionException exception) {
                throw unwrap(exception);
            }
        };
        Callable<StepExecution> retried = executionPolicy.retryEnabled()
                ? Retry.decorateCallable(stepControls.retry(), timedAttempt)
                : timedAttempt;
        Callable<StepExecution> guarded = CircuitBreaker.decorateCallable(
                stepControls.circuitBreaker(),
                retried
        );
        return guarded.call();
    }

    public CircuitBreaker.State circuitState(String scope) {
        Controls stepControls = controls.get(scope);
        return stepControls == null
                ? CircuitBreaker.State.CLOSED
                : stepControls.circuitBreaker().getState();
    }

    /**
     * 执行本地降级路径，仅应用超时，不进行重试，也不再次经过熔断器保护。
     */
    public StepExecution executeFallback(
            String scope,
            AttemptSupplier supplier,
            IntConsumer beforeAttempt
    ) throws Exception {
        if (closed) {
            throw new IllegalStateException("step executor is closed");
        }
        Controls fallbackControls = controls.computeIfAbsent(
                scope + ":fallback",
                this::createControls
        );
        beforeAttempt.accept(1);
        Future<StepResult> future = actionExecutor.submit(() -> supplier.execute(1));
        try {
            StepResult result = fallbackControls.timeLimiter()
                    .executeFutureSupplier(() -> future);
            return new StepExecution(result, 1);
        } catch (ExecutionException exception) {
            throw unwrap(exception);
        }
    }

    /**
     * 为普通外部调用提供超时、可选重试与熔断保护。
     */
    public <T> T executeValue(
            String scope,
            ExecutionPolicy executionPolicy,
            Callable<T> action
    ) throws Exception {
        if (closed) {
            throw new IllegalStateException("step executor is closed");
        }
        Controls callControls = controls.computeIfAbsent(
                scope,
                this::createControls
        );
        Callable<T> timed = () -> callControls.timeLimiter()
                .executeFutureSupplier(() -> actionExecutor.submit(action));
        Callable<T> retried = executionPolicy.retryEnabled()
                ? Retry.decorateCallable(callControls.retry(), timed)
                : timed;
        return CircuitBreaker.decorateCallable(
                callControls.circuitBreaker(),
                retried
        ).call();
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        actionExecutor.shutdownNow();
    }

    private Controls createControls(String scope) {
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(policy.failureRateThreshold())
                .slidingWindowSize(policy.slidingWindowSize())
                .minimumNumberOfCalls(policy.minimumCalls())
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(policy.circuitOpenWait())
                .recordExceptions(
                        RetryableStepException.class,
                        TimeoutException.class,
                        RuntimeException.class
                )
                .ignoreExceptions(StepRejectedException.class)
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(policy.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        policy.initialBackoff(),
                        policy.backoffMultiplier()
                ))
                .retryOnException(this::isRetryable)
                .build();
        TimeLimiterConfig timeoutConfig = TimeLimiterConfig.custom()
                .timeoutDuration(policy.timeoutPerAttempt())
                .cancelRunningFuture(true)
                .build();
        return new Controls(
                CircuitBreaker.of(scope, circuitConfig),
                Retry.of(scope, retryConfig),
                TimeLimiter.of(scope, timeoutConfig)
        );
    }

    private boolean isRetryable(Throwable throwable) {
        return throwable instanceof RetryableStepException
                || throwable instanceof TimeoutException;
    }

    private Exception unwrap(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof Exception checked) {
            return checked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return exception;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    public interface AttemptSupplier {
        StepResult execute(int attempt) throws Exception;
    }

    private record Controls(
            CircuitBreaker circuitBreaker,
            Retry retry,
            TimeLimiter timeLimiter
    ) {
    }
}
