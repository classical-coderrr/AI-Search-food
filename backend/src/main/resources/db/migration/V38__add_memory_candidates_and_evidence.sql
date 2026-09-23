CREATE TABLE memory_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    candidate_type VARCHAR(64) NOT NULL,
    entity VARCHAR(255) NOT NULL,
    preference VARCHAR(64) NOT NULL,
    strength DECIMAL(5,4) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'USER',
    temporal_type VARCHAR(32) NOT NULL DEFAULT 'RECENT',
    evidence_count INT NOT NULL DEFAULT 1,
    extraction_key VARCHAR(192) NOT NULL,
    extraction_model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    extracted_at DATETIME NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_candidates_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_candidates_episode FOREIGN KEY (episode_id) REFERENCES memory_episodes(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_candidates_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_memory_candidates_user_key
    ON memory_candidates(user_id, extraction_key);
CREATE INDEX idx_memory_candidates_user_episode
    ON memory_candidates(user_id, episode_id, id);
CREATE INDEX idx_memory_candidates_user_type
    ON memory_candidates(user_id, candidate_type, confidence DESC, id DESC);
CREATE INDEX idx_memory_candidates_user_entity
    ON memory_candidates(user_id, entity, preference, id DESC);

CREATE TABLE memory_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(96) NULL,
    source_session_id BIGINT NULL,
    evidence_key VARCHAR(192) NOT NULL,
    evidence_text VARCHAR(512) NOT NULL,
    evidence_json TEXT NOT NULL,
    explicit_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    observed_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_evidence_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_evidence_candidate FOREIGN KEY (candidate_id) REFERENCES memory_candidates(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_evidence_episode FOREIGN KEY (episode_id) REFERENCES memory_episodes(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_evidence_session FOREIGN KEY (source_session_id) REFERENCES agent_sessions(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_memory_evidence_candidate_key
    ON memory_evidence(candidate_id, evidence_key);
CREATE INDEX idx_memory_evidence_user_candidate
    ON memory_evidence(user_id, candidate_id, id);
CREATE INDEX idx_memory_evidence_user_episode
    ON memory_evidence(user_id, episode_id, observed_at DESC, id DESC);
