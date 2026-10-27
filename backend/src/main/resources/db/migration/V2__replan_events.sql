CREATE TABLE travel_event (
    id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    poi_id VARCHAR(64),
    current_item_id VARCHAR(64),
    delay_minutes INTEGER,
    reason TEXT,
    source VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_travel_event_plan FOREIGN KEY (plan_id) REFERENCES trip_plan(id)
);

CREATE TABLE replan_proposal (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    plan_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    base_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(500),
    proposed_plan_json TEXT NOT NULL,
    changes_json TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_replan_event FOREIGN KEY (event_id) REFERENCES travel_event(id),
    CONSTRAINT fk_replan_plan FOREIGN KEY (plan_id) REFERENCES trip_plan(id)
);

CREATE INDEX idx_travel_event_plan_time ON travel_event(plan_id, occurred_at DESC);
CREATE INDEX idx_replan_proposal_status ON replan_proposal(status, created_at DESC);
