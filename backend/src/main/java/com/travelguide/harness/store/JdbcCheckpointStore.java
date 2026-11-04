package com.travelguide.harness.store;

import com.travelguide.harness.RunCheckpoint;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

public final class JdbcCheckpointStore implements CheckpointStore {
    private static final String UPDATE_SQL = """
            UPDATE agent_workflow_checkpoint
               SET workflow_id = ?,
                   workflow_version = ?,
                   checkpoint_json = ?,
                   updated_at = ?
             WHERE run_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO agent_workflow_checkpoint
                (run_id, workflow_id, workflow_version, checkpoint_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String SELECT_SQL = """
            SELECT checkpoint_json
              FROM agent_workflow_checkpoint
             WHERE run_id = ?
            """;

    private final DataSource dataSource;
    private final JsonCodec codec = new JsonCodec();

    public JdbcCheckpointStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
        String json = codec.write(checkpoint);
        Timestamp updatedAt = Timestamp.from(checkpoint.updatedAt());
        try (Connection connection = dataSource.getConnection()) {
            if (update(connection, checkpoint, json, updatedAt) == 0) {
                insert(connection, checkpoint, json, updatedAt);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot save workflow checkpoint", exception);
        }
    }

    @Override
    public Optional<RunCheckpoint> find(String runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(codec.read(
                        resultSet.getString("checkpoint_json"),
                        RunCheckpoint.class
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot load workflow checkpoint", exception);
        }
    }

    private int update(
            Connection connection,
            RunCheckpoint checkpoint,
            String json,
            Timestamp updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            statement.setString(1, checkpoint.workflowId());
            statement.setString(2, checkpoint.workflowVersion());
            statement.setString(3, json);
            statement.setTimestamp(4, updatedAt);
            statement.setString(5, checkpoint.runId());
            return statement.executeUpdate();
        }
    }

    private void insert(
            Connection connection,
            RunCheckpoint checkpoint,
            String json,
            Timestamp updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, checkpoint.runId());
            statement.setString(2, checkpoint.workflowId());
            statement.setString(3, checkpoint.workflowVersion());
            statement.setString(4, json);
            statement.setTimestamp(5, updatedAt);
            statement.executeUpdate();
        }
    }
}
