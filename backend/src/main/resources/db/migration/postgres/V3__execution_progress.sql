CREATE TABLE trip_execution_progress (
    id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL UNIQUE,
    current_item_id VARCHAR(64),
    completed_item_ids_json TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL,
    current_item_started_at TIMESTAMP,
    actual_ended_at TIMESTAMP,
    delay_minutes INTEGER NOT NULL DEFAULT 0,
    last_event_id VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_progress_plan FOREIGN KEY (plan_id) REFERENCES trip_plan(id)
);

CREATE INDEX idx_progress_updated_at ON trip_execution_progress(updated_at DESC);
