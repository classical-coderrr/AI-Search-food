CREATE TABLE memory_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    feedback_type VARCHAR(24) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_memory_feedback_trace UNIQUE (trace_id),
    CONSTRAINT fk_memory_feedback_trace
        FOREIGN KEY (trace_id) REFERENCES memory_retrieval_traces (trace_id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_feedback_user_updated
    ON memory_feedback(user_id, updated_at, id);

CREATE INDEX idx_memory_feedback_type_updated
    ON memory_feedback(feedback_type, updated_at, id);
