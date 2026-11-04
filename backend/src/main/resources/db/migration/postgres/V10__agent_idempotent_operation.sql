CREATE TABLE IF NOT EXISTS agent_idempotent_operation (
    operation_key VARCHAR(512) PRIMARY KEY,
    result_type VARCHAR(256) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
