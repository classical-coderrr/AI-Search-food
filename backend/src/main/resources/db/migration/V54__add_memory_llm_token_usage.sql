ALTER TABLE memory_retrieval_traces
    ADD COLUMN llm_input_tokens BIGINT NULL;

ALTER TABLE memory_retrieval_traces
    ADD COLUMN llm_output_tokens BIGINT NULL;

ALTER TABLE memory_retrieval_traces
    ADD COLUMN llm_total_tokens BIGINT NULL;

ALTER TABLE memory_retrieval_traces
    ADD COLUMN llm_usage_call_count INT NOT NULL DEFAULT 0;
