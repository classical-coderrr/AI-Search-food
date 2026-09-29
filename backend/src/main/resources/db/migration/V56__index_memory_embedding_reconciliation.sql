CREATE INDEX idx_memory_embeddings_ann_reconciliation
    ON memory_embeddings(embedding_model, ann_indexed_at, id);
