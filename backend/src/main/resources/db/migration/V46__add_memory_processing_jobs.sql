CREATE TABLE memory_processing_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(64),
    lease_until DATETIME,
    last_error VARCHAR(500),
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_processing_jobs_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_processing_jobs_episode FOREIGN KEY (episode_id)
        REFERENCES memory_episodes(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_memory_processing_jobs_user_episode
    ON memory_processing_jobs(user_id, episode_id);
CREATE INDEX idx_memory_processing_jobs_claim
    ON memory_processing_jobs(status, available_at, lease_until, id);
