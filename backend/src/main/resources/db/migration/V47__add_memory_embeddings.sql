CREATE TABLE memory_embeddings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_id BIGINT NOT NULL,
    memory_type VARCHAR(64) NOT NULL,
    source_version INT NOT NULL DEFAULT 0,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    embedding_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_embeddings_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_memory_embeddings_source_kind CHECK (source_kind IN ('MEMORY_ITEM', 'EPISODE')),
    CONSTRAINT ck_memory_embeddings_dimensions CHECK (dimensions > 0)
);

CREATE UNIQUE INDEX uq_memory_embeddings_source_model
    ON memory_embeddings(user_id, source_kind, source_id, embedding_model);
CREATE INDEX idx_memory_embeddings_user_model
    ON memory_embeddings(user_id, embedding_model, id);
