CREATE TABLE agent_write_operations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    confirmation_id BIGINT NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
    result_json MEDIUMTEXT,
    result_message VARCHAR(512),
    error_code VARCHAR(64),
    error_message TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_write_operations_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_agent_write_operations_confirmation
        FOREIGN KEY (confirmation_id) REFERENCES agent_confirmations(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_write_operations_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_write_operations_user_status
    ON agent_write_operations(user_id, status, updated_at DESC);
