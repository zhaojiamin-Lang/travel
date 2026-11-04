CREATE TABLE app_user (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255),
    guest BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE trip_request (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(128),
    owner_id VARCHAR(64),
    raw_prompt TEXT,
    city VARCHAR(80),
    days INTEGER,
    start_date DATE,
    end_date DATE,
    people INTEGER,
    budget NUMERIC(12, 2),
    pace VARCHAR(32),
    no_spicy BOOLEAN,
    must_see VARCHAR(1024),
    summary TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE poi (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    alias_name VARCHAR(255),
    type VARCHAR(64),
    area VARCHAR(80),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    duration_minutes INTEGER,
    ticket_price NUMERIC(12, 2),
    open_time VARCHAR(80),
    close_time VARCHAR(80),
    tags VARCHAR(1024),
    accent VARCHAR(32),
    summary TEXT,
    history TEXT,
    guide_text TEXT,
    source_name VARCHAR(255),
    source_url VARCHAR(1024),
    data_confidence VARCHAR(32)
);

CREATE TABLE trip_plan (
    id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    passed BOOLEAN,
    plan_json TEXT NOT NULL,
    issues_json TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_trip_plan_request_version UNIQUE (request_id, version),
    CONSTRAINT fk_trip_plan_request FOREIGN KEY (request_id) REFERENCES trip_request(id)
);

CREATE TABLE guide_session (
    id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    current_poi_id VARCHAR(64),
    status VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_guide_plan FOREIGN KEY (plan_id) REFERENCES trip_plan(id),
    CONSTRAINT fk_guide_poi FOREIGN KEY (current_poi_id) REFERENCES poi(id)
);

CREATE INDEX idx_trip_request_owner_created ON trip_request(owner_id, created_at DESC);
CREATE INDEX idx_trip_plan_request_version ON trip_plan(request_id, version DESC);
CREATE INDEX idx_poi_area_type ON poi(area, type);
CREATE INDEX idx_guide_session_plan ON guide_session(plan_id);
