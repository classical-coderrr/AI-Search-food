ALTER TABLE memory_embeddings
    ADD COLUMN ann_indexed_at DATETIME NULL;

CREATE TABLE memory_vector_index_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_id BIGINT NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    source_version INT,
    dimensions INT NOT NULL DEFAULT 0,
    operation VARCHAR(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(64),
    lease_until DATETIME,
    last_error VARCHAR(128),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_memory_vector_index_jobs_source_kind
        CHECK (source_kind IN ('MEMORY_ITEM', 'EPISODE', 'USER')),
    CONSTRAINT ck_memory_vector_index_jobs_operation
        CHECK (operation IN ('UPSERT', 'DELETE_SOURCE', 'DELETE_USER')),
    CONSTRAINT ck_memory_vector_index_jobs_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETE')),
    CONSTRAINT ck_memory_vector_index_jobs_dimensions CHECK (dimensions >= 0)
);

CREATE INDEX idx_memory_vector_index_jobs_claim
    ON memory_vector_index_jobs(status, available_at, lease_until, id);
CREATE INDEX idx_memory_vector_index_jobs_user_order
    ON memory_vector_index_jobs(user_id, id, status);
CREATE INDEX idx_memory_vector_index_jobs_source
    ON memory_vector_index_jobs(user_id, source_kind, source_id, embedding_model, source_version, status);
