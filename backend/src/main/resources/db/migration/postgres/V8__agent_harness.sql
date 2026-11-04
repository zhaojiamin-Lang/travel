CREATE TABLE IF NOT EXISTS agent_workflow_checkpoint (
    run_id VARCHAR(128) PRIMARY KEY,
    workflow_id VARCHAR(128) NOT NULL,
    workflow_version VARCHAR(64) NOT NULL,
    checkpoint_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_idempotency_record (
    idempotency_key VARCHAR(512) PRIMARY KEY,
    request_fingerprint VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(128),
    lease_until TIMESTAMP WITH TIME ZONE,
    result_json TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_checkpoint_updated
    ON agent_workflow_checkpoint (updated_at);

CREATE INDEX IF NOT EXISTS idx_agent_idempotency_lease
    ON agent_idempotency_record (status, lease_until);
