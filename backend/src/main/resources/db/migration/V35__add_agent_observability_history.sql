CREATE TABLE agent_metric_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instance_id VARCHAR(128) NOT NULL,
    captured_at DATETIME NOT NULL,
    runs_started BIGINT NOT NULL DEFAULT 0,
    runs_completed BIGINT NOT NULL DEFAULT 0,
    runs_failed BIGINT NOT NULL DEFAULT 0,
    runs_recovered BIGINT NOT NULL DEFAULT 0,
    events_persisted BIGINT NOT NULL DEFAULT 0,
    events_replayed BIGINT NOT NULL DEFAULT 0,
    duplicate_writes BIGINT NOT NULL DEFAULT 0,
    duration_samples BIGINT NOT NULL DEFAULT 0,
    average_run_duration_ms DECIMAL(18, 3) NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_metric_snapshots_captured_at
    ON agent_metric_snapshots(captured_at, id);

CREATE TABLE agent_observability_alerts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(500) NOT NULL,
    metric_value DECIMAL(18, 6) NOT NULL DEFAULT 0,
    threshold_value DECIMAL(18, 6) NOT NULL DEFAULT 0,
    dedupe_key VARCHAR(128) NOT NULL,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    resolved_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_observability_alerts_dedupe UNIQUE (dedupe_key)
);

CREATE INDEX idx_agent_observability_alerts_status_seen
    ON agent_observability_alerts(status, last_seen_at, id);
