package com.travelguide.harness.store;

import com.travelguide.harness.StepResult;
import com.travelguide.harness.store.IdempotencyStore.IdempotencyClaim;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIdempotencyStore implements IdempotencyStore {
    private static final String SELECT_SQL = """
            SELECT request_fingerprint, status, claim_token, lease_until, result_json
              FROM agent_idempotency_record
             WHERE idempotency_key = ?
             FOR UPDATE
            """;
    private static final String INSERT_SQL = """
            INSERT INTO agent_idempotency_record
                (idempotency_key, request_fingerprint, status, claim_token,
                 lease_until, result_json, updated_at)
            VALUES (?, ?, 'CLAIMED', ?, ?, NULL, ?)
            """;
    private static final String RECLAIM_SQL = """
            UPDATE agent_idempotency_record
               SET status = 'CLAIMED',
                   claim_token = ?,
                   lease_until = ?,
                   result_json = NULL,
                   updated_at = ?
             WHERE idempotency_key = ?
            """;
    private static final String COMPLETE_SQL = """
            UPDATE agent_idempotency_record
               SET status = 'COMPLETED',
                   claim_token = NULL,
                   lease_until = NULL,
                   result_json = ?,
                   updated_at = ?
             WHERE idempotency_key = ?
               AND status = 'CLAIMED'
               AND claim_token = ?
            """;
    private static final String RELEASE_SQL = """
            DELETE FROM agent_idempotency_record
             WHERE idempotency_key = ?
               AND status = 'CLAIMED'
               AND claim_token = ?
            """;

    private final DataSource dataSource;
    private final Clock clock;
    private final JsonCodec codec = new JsonCodec();

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcIdempotencyStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public IdempotencyClaim begin(
            String key,
            String requestFingerprint,
            Duration leaseDuration
    ) {
        SQLException firstFailure;
        try {
            return beginOnce(key, requestFingerprint, leaseDuration);
        } catch (SQLException exception) {
            firstFailure = exception;
        }
        if (!isUniqueViolation(firstFailure)) {
            throw failure("cannot claim idempotency key", firstFailure);
        }
        try {
            return beginOnce(key, requestFingerprint, leaseDuration);
        } catch (SQLException exception) {
            throw failure("cannot claim idempotency key", exception);
        }
    }

    @Override
    public void complete(String key, String claimToken, StepResult result) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COMPLETE_SQL)) {
            statement.setString(1, codec.write(result));
            statement.setTimestamp(2, Timestamp.from(clock.instant()));
            statement.setString(3, key);
            statement.setString(4, claimToken);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("idempotency claim is not owned");
            }
        } catch (SQLException exception) {
            throw failure("cannot complete idempotency key", exception);
        }
    }

    @Override
    public void release(String key, String claimToken) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RELEASE_SQL)) {
            statement.setString(1, key);
            statement.setString(2, claimToken);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("cannot release idempotency key", exception);
        }
    }

    private IdempotencyClaim beginOnce(
            String key,
            String requestFingerprint,
            Duration leaseDuration
    ) throws SQLException {
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                IdempotencyClaim existing = readExisting(
                        connection,
                        key,
                        requestFingerprint,
                        leaseDuration,
                        now
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                String token = UUID.randomUUID().toString();
                try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                    insert.setString(1, key);
                    insert.setString(2, requestFingerprint);
                    insert.setString(3, token);
                    insert.setTimestamp(4, Timestamp.from(now.plus(leaseDuration)));
                    insert.setTimestamp(5, Timestamp.from(now));
                    insert.executeUpdate();
                }
                connection.commit();
                return IdempotencyClaim.claimed(token);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private IdempotencyClaim readExisting(
            Connection connection,
            String key,
            String requestFingerprint,
            Duration leaseDuration,
            Instant now
    ) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SQL)) {
            select.setString(1, key);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                if (!requestFingerprint.equals(
                        resultSet.getString("request_fingerprint")
                )) {
                    return IdempotencyClaim.conflict();
                }
                if ("COMPLETED".equals(resultSet.getString("status"))) {
                    String json = resultSet.getString("result_json");
                    if (json == null) {
                        throw new IllegalStateException(
                                "completed idempotency record has no result"
                        );
                    }
                    return IdempotencyClaim.completed(
                            codec.read(json, StepResult.class)
                    );
                }
                Timestamp leaseUntil = resultSet.getTimestamp("lease_until");
                if (leaseUntil != null
                        && leaseUntil.toInstant().isAfter(now)) {
                    return IdempotencyClaim.busy(
                            Duration.between(now, leaseUntil.toInstant())
                    );
                }
                String token = UUID.randomUUID().toString();
                try (PreparedStatement update = connection.prepareStatement(
                        RECLAIM_SQL
                )) {
                    update.setString(1, token);
                    update.setTimestamp(2, Timestamp.from(now.plus(leaseDuration)));
                    update.setTimestamp(3, Timestamp.from(now));
                    update.setString(4, key);
                    update.executeUpdate();
                }
                return IdempotencyClaim.claimed(token);
            }
        }
    }

    private boolean isUniqueViolation(SQLException exception) {
        return "23505".equals(exception.getSQLState());
    }

    private IllegalStateException failure(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }
}
