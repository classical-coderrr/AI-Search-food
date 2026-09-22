CREATE TABLE agent_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    session_key VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    current_task VARCHAR(128),
    current_goal VARCHAR(255),
    context_json TEXT,
    selected_memory_ids_json TEXT,
    retrieved_knowledge_ids_json TEXT,
    agent_state_json TEXT,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    ended_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_sessions_conversation FOREIGN KEY (conversation_id)
        REFERENCES agent_conversations(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_agent_sessions_user_key
    ON agent_sessions(user_id, session_key);
CREATE INDEX idx_agent_sessions_user_status_activity
    ON agent_sessions(user_id, status, last_activity_at, id);
CREATE INDEX idx_agent_sessions_expiry
    ON agent_sessions(status, expires_at);

CREATE TABLE memory_episodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    conversation_id BIGINT NULL,
    episode_type VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(96),
    event_id VARCHAR(96),
    idempotency_key VARCHAR(128) NOT NULL,
    summary VARCHAR(512),
    payload_json TEXT NOT NULL,
    occurred_at DATETIME NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RAW',
    importance DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_episodes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_episodes_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id) ON DELETE SET NULL,
    CONSTRAINT fk_memory_episodes_conversation FOREIGN KEY (conversation_id)
        REFERENCES agent_conversations(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_memory_episodes_user_key
    ON memory_episodes(user_id, idempotency_key);
CREATE INDEX idx_memory_episodes_user_occurred
    ON memory_episodes(user_id, occurred_at DESC, id DESC);
CREATE INDEX idx_memory_episodes_user_type_occurred
    ON memory_episodes(user_id, episode_type, occurred_at DESC, id DESC);
CREATE INDEX idx_memory_episodes_session_occurred
    ON memory_episodes(session_id, occurred_at DESC, id DESC);
CREATE INDEX idx_memory_episodes_event
    ON memory_episodes(user_id, event_id);
