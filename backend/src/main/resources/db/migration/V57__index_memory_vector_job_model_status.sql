CREATE INDEX idx_memory_vector_jobs_model_operation_status
    ON memory_vector_index_jobs(embedding_model, operation, status);
