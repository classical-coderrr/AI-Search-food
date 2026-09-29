CREATE TABLE user_knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    storage_key VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    extracted_characters INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(300),
    version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_knowledge_documents_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_knowledge_documents_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'))
);

CREATE UNIQUE INDEX uq_user_knowledge_documents_storage_key ON user_knowledge_documents(storage_key);
CREATE INDEX idx_user_knowledge_documents_owner_status ON user_knowledge_documents(user_id, deleted_at, status, created_at);

CREATE TABLE user_knowledge_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_knowledge_chunks_document FOREIGN KEY (document_id) REFERENCES user_knowledge_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_knowledge_chunks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_user_knowledge_chunks_document_ordinal ON user_knowledge_chunks(document_id, ordinal);
CREATE INDEX idx_user_knowledge_chunks_owner_document ON user_knowledge_chunks(user_id, document_id, id);

CREATE TABLE user_knowledge_embeddings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    embedding_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_knowledge_embeddings_document FOREIGN KEY (document_id) REFERENCES user_knowledge_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_knowledge_embeddings_chunk FOREIGN KEY (chunk_id) REFERENCES user_knowledge_chunks(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_knowledge_embeddings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_knowledge_embeddings_dimensions CHECK (dimensions > 0)
);

CREATE UNIQUE INDEX uq_user_knowledge_embeddings_chunk_model ON user_knowledge_embeddings(user_id, chunk_id, embedding_model);
CREATE INDEX idx_user_knowledge_embeddings_owner_model ON user_knowledge_embeddings(user_id, embedding_model, document_id);

CREATE TABLE knowledge_processing_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(64),
    lease_until DATETIME,
    last_error VARCHAR(300),
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_processing_jobs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_processing_jobs_document FOREIGN KEY (document_id) REFERENCES user_knowledge_documents(id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_processing_jobs_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_knowledge_processing_jobs_user_document ON knowledge_processing_jobs(user_id, document_id);
CREATE INDEX idx_knowledge_processing_jobs_claim ON knowledge_processing_jobs(status, available_at, lease_until, id);
