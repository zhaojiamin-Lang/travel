package com.travelguide.harness;

import com.travelguide.harness.store.JdbcCheckpointStore;
import com.travelguide.harness.store.JdbcIdempotencyStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHarnessPersistenceTest {
    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:harness_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_workflow_checkpoint (
                        run_id VARCHAR(128) PRIMARY KEY,
                        workflow_id VARCHAR(128) NOT NULL,
                        workflow_version VARCHAR(64) NOT NULL,
                        checkpoint_json CLOB NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE agent_idempotency_record (
                        idempotency_key VARCHAR(512) PRIMARY KEY,
                        request_fingerprint VARCHAR(512) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        claim_token VARCHAR(128),
                        lease_until TIMESTAMP WITH TIME ZONE,
                        result_json CLOB,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }
    }

    @Test
    void checkpointRoundTripsThroughJdbc() {
        JdbcCheckpointStore store = new JdbcCheckpointStore(dataSource);
        Instant now = Instant.parse("2026-11-01T01:02:03Z");
        RunCheckpoint checkpoint = new RunCheckpoint(
                "run-jdbc",
                "trip",
                "v3",
                RunState.CHECKPOINTED,
                1,
                List.of(ContextItem.preserved(
                        "plan",
                        ContextKind.PLAN_IR,
                        "{\"days\":3}",
                        now
                )),
                List.of(new StepRecord(
                        "requirements",
                        "normalized",
                        Map.of("source", "test"),
                        1,
                        false,
                        now
                )),
                null,
                null,
                false,
                4,
                now,
                now.plus(Duration.ofMinutes(5)),
                now
        );

        store.save(checkpoint);

        assertThat(store.find("run-jdbc")).contains(checkpoint);
    }

    @Test
    void idempotencyClaimPersistsCompletedResult() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource);
        String key = "run:step";
        String fingerprint = "workflow:v1:step";

        var claim = store.begin(key, fingerprint, Duration.ofSeconds(30));
        assertThat(claim.decision())
                .isEqualTo(com.travelguide.harness.store.IdempotencyStore.Decision.CLAIMED);
        store.complete(
                key,
                claim.claimToken(),
                new StepResult("done", Map.of("cached", "yes"), List.of())
        );

        var replay = store.begin(key, fingerprint, Duration.ofSeconds(30));
        assertThat(replay.decision())
                .isEqualTo(com.travelguide.harness.store.IdempotencyStore.Decision.COMPLETED);
        assertThat(replay.result()).contains(
                new StepResult("done", Map.of("cached", "yes"), List.of())
        );
    }
}
