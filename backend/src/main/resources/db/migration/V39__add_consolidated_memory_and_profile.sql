CREATE TABLE memory_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    memory_type VARCHAR(64) NOT NULL,
    canonical_entity VARCHAR(255) NOT NULL,
    preference VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'USER',
    temporal_type VARCHAR(32) NOT NULL DEFAULT 'RECENT',
    strength DECIMAL(5,4) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    importance DECIMAL(5,4) NOT NULL,
    evidence_count INT NOT NULL DEFAULT 0,
    occurrence_count INT NOT NULL DEFAULT 0,
    source_count INT NOT NULL DEFAULT 0,
    source_candidate_ids_json TEXT NOT NULL,
    source_episode_ids_json TEXT NOT NULL,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    consolidation_key VARCHAR(192) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_items_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_memory_items_user_key
    ON memory_items(user_id, consolidation_key);
CREATE INDEX idx_memory_items_user_type
    ON memory_items(user_id, memory_type, confidence DESC, last_seen_at DESC, id DESC);
CREATE INDEX idx_memory_items_user_entity
    ON memory_items(user_id, canonical_entity, preference, temporal_type, id DESC);

CREATE TABLE memory_item_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    memory_item_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_item_candidates_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_item_candidates_item FOREIGN KEY (memory_item_id) REFERENCES memory_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_item_candidates_candidate FOREIGN KEY (candidate_id) REFERENCES memory_candidates(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_memory_item_candidates_pair
    ON memory_item_candidates(memory_item_id, candidate_id);
CREATE INDEX idx_memory_item_candidates_user_candidate
    ON memory_item_candidates(user_id, candidate_id, memory_item_id);

CREATE TABLE memory_profiles (
    user_id BIGINT PRIMARY KEY,
    profile_json TEXT NOT NULL,
    profile_version INT NOT NULL DEFAULT 1,
    source_revision BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    rebuilt_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_profiles_rebuilt
    ON memory_profiles(rebuilt_at, user_id);
