CREATE TABLE personalized_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    skill_name VARCHAR(64) NOT NULL,
    strategy_json TEXT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0,
    evidence_count INT NOT NULL DEFAULT 0,
    source_memory_ids_json TEXT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    source_profile_version INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_personalized_skill_user_name UNIQUE (user_id, skill_name),
    CONSTRAINT fk_personalized_skill_profile FOREIGN KEY (user_id)
        REFERENCES memory_profiles(user_id) ON DELETE CASCADE,
    CONSTRAINT ck_personalized_skill_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_personalized_skill_evidence CHECK (evidence_count >= 0)
);

CREATE INDEX idx_personalized_skill_user_active
    ON personalized_skill(user_id, deleted_at, skill_name);
