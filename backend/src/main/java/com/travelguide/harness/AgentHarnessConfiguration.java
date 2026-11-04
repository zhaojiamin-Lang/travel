package com.travelguide.harness;

import com.travelguide.harness.store.CheckpointStore;
import com.travelguide.harness.store.IdempotencyStore;
import com.travelguide.harness.store.JdbcCheckpointStore;
import com.travelguide.harness.store.JdbcIdempotencyStore;
import com.travelguide.harness.resilience.Resilience4jStepExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class AgentHarnessConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public HarnessPolicy harnessPolicy() {
        return HarnessPolicy.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "travel.harness.store",
            havingValue = "jdbc",
            matchIfMissing = true
    )
    public CheckpointStore checkpointStore(DataSource dataSource) {
        return new JdbcCheckpointStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "travel.harness.store",
            havingValue = "jdbc",
            matchIfMissing = true
    )
    public IdempotencyStore idempotencyStore(DataSource dataSource) {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    public Resilience4jStepExecutor resilience4jStepExecutor(HarnessPolicy policy) {
        return new Resilience4jStepExecutor(policy);
    }

    @Bean
    @ConditionalOnMissingBean
    public HarnessEventListener harnessEventListener(MeterRegistry meterRegistry) {
        return event -> meterRegistry.counter(
                "agent.harness.events",
                "state", event.state().name(),
                "workflow", event.workflowId()
        ).increment();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentHarness agentHarness(
            CheckpointStore checkpointStore,
            IdempotencyStore idempotencyStore,
            HarnessPolicy policy,
            Resilience4jStepExecutor stepExecutor,
            HarnessEventListener eventListener
    ) {
        return new AgentHarness(
                checkpointStore,
                idempotencyStore,
                new ContextWindowCompactor(),
                policy,
                eventListener,
                java.time.Clock.systemUTC(),
                stepExecutor,
                false
        );
    }
}
