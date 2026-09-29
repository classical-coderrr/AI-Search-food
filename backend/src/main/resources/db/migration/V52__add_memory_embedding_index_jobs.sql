CREATE TABLE memory_embedding_index_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_id BIGINT NOT NULL,
    source_version INT NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(64),
    lease_until DATETIME,
    last_error VARCHAR(500),
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_embedding_index_jobs_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_memory_embedding_index_jobs_source_kind
        CHECK (source_kind IN ('MEMORY_ITEM', 'EPISODE')),
    CONSTRAINT ck_memory_embedding_index_jobs_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETE')),
    CONSTRAINT ck_memory_embedding_index_jobs_dimensions CHECK (dimensions > 0)
);

CREATE UNIQUE INDEX uq_memory_embedding_index_jobs_source_model
    ON memory_embedding_index_jobs(user_id, source_kind, source_id, embedding_model);
CREATE INDEX idx_memory_embedding_index_jobs_claim
    ON memory_embedding_index_jobs(status, available_at, lease_until, id);
