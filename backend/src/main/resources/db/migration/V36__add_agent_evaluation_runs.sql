CREATE TABLE agent_evaluation_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NOT NULL,
    total_cases INT NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_evaluation_runs_started_at
    ON agent_evaluation_runs(started_at, id);

CREATE TABLE agent_evaluation_case_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_key VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    input_message VARCHAR(500) NOT NULL,
    passed BOOLEAN NOT NULL,
    expected_tools TEXT NOT NULL,
    actual_tools TEXT NOT NULL,
    failure_reason VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_evaluation_case_results_run_id
    ON agent_evaluation_case_results(run_id, id);
