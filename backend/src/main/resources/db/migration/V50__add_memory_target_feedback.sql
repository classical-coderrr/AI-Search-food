CREATE UNIQUE INDEX uq_memory_episodes_user_id_id
    ON memory_episodes(user_id, id);
CREATE UNIQUE INDEX uq_memory_retrieval_trace_user_trace
    ON memory_retrieval_traces(user_id, trace_id);

CREATE TABLE memory_target_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    intent VARCHAR(64) NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    memory_item_id BIGINT NULL,
    episode_id BIGINT NULL,
    feedback_type VARCHAR(24) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_memory_target_feedback_source CHECK (
        (source_kind = 'MEMORY_ITEM' AND memory_item_id IS NOT NULL AND episode_id IS NULL)
        OR (source_kind = 'EPISODE' AND episode_id IS NOT NULL AND memory_item_id IS NULL)
    ),
    CONSTRAINT uq_memory_target_feedback_trace_item UNIQUE (trace_id, memory_item_id),
    CONSTRAINT uq_memory_target_feedback_trace_episode UNIQUE (trace_id, episode_id),
    CONSTRAINT fk_memory_target_feedback_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_target_feedback_trace FOREIGN KEY (user_id, trace_id)
        REFERENCES memory_retrieval_traces(user_id, trace_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_target_feedback_item FOREIGN KEY (user_id, memory_item_id)
        REFERENCES memory_items(user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_target_feedback_episode FOREIGN KEY (user_id, episode_id)
        REFERENCES memory_episodes(user_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_target_feedback_item_intent
    ON memory_target_feedback(user_id, intent, memory_item_id, updated_at DESC);

CREATE INDEX idx_memory_target_feedback_episode_intent
    ON memory_target_feedback(user_id, intent, episode_id, updated_at DESC);
