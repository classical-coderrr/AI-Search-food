CREATE UNIQUE INDEX uq_memory_items_user_id_id
    ON memory_items(user_id, id);

CREATE TABLE memory_conflicts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    session_id BIGINT NULL,
    conflict_key VARCHAR(192) NOT NULL,
    conflict_domain VARCHAR(24) NOT NULL,
    canonical_entity VARCHAR(255) NOT NULL,
    like_memory_item_id BIGINT NOT NULL,
    dislike_memory_item_id BIGINT NOT NULL,
    selected_memory_item_id BIGINT NULL,
    selected_preference VARCHAR(16) NOT NULL,
    resolution_type VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    context_json TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_memory_conflicts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_conflicts_like_item FOREIGN KEY (user_id, like_memory_item_id)
        REFERENCES memory_items(user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_conflicts_dislike_item FOREIGN KEY (user_id, dislike_memory_item_id)
        REFERENCES memory_items(user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_conflicts_selected_item FOREIGN KEY (user_id, selected_memory_item_id)
        REFERENCES memory_items(user_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_memory_conflicts_trace_key
    ON memory_conflicts(user_id, trace_id, conflict_key);
CREATE INDEX idx_memory_conflicts_user_created
    ON memory_conflicts(user_id, created_at DESC, id DESC);
CREATE INDEX idx_memory_conflicts_user_key
    ON memory_conflicts(user_id, conflict_key, created_at DESC);
