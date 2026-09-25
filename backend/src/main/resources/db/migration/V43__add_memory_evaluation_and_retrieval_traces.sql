CREATE TABLE memory_retrieval_traces (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    intent VARCHAR(64) NULL,
    query_hash CHAR(64) NOT NULL,
    memory_item_candidates INT NOT NULL DEFAULT 0,
    episode_candidates INT NOT NULL DEFAULT 0,
    retrieved_memory_item_ids_json TEXT NOT NULL,
    retrieved_episode_ids_json TEXT NOT NULL,
    used_memory_item_ids_json TEXT NOT NULL,
    used_episode_ids_json TEXT NOT NULL,
    knowledge_ids_json TEXT NOT NULL,
    ranking_json TEXT NOT NULL,
    context_sections_json TEXT NOT NULL,
    estimated_tokens INT NOT NULL DEFAULT 0,
    token_budget INT NOT NULL DEFAULT 0,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    error_type VARCHAR(128) NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_memory_retrieval_traces_trace
    ON memory_retrieval_traces(trace_id);
CREATE INDEX idx_memory_retrieval_traces_user_created
    ON memory_retrieval_traces(user_id, created_at, id);
CREATE INDEX idx_memory_retrieval_traces_status_created
    ON memory_retrieval_traces(status, created_at, id);

CREATE TABLE memory_evaluation_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suite_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NOT NULL,
    total_cases INT NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    result_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_memory_evaluation_runs_started
    ON memory_evaluation_runs(started_at, id);
