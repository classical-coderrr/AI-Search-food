ALTER TABLE memory_candidates ADD COLUMN user_decision VARCHAR(32) NULL;
ALTER TABLE memory_candidates ADD COLUMN decided_at DATETIME NULL;

CREATE INDEX idx_memory_candidates_user_status_time
    ON memory_candidates(user_id, status, extracted_at DESC);
