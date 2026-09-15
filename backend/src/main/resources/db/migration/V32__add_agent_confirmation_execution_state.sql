ALTER TABLE agent_confirmations
    ADD COLUMN processing_at DATETIME NULL;

ALTER TABLE agent_confirmations
    ADD COLUMN result_message VARCHAR(512) NULL;

ALTER TABLE agent_confirmations
    ADD COLUMN error_code VARCHAR(64) NULL;

ALTER TABLE agent_confirmations
    ADD COLUMN error_message TEXT NULL;

CREATE INDEX idx_agent_confirmations_processing
    ON agent_confirmations(status, processing_at);
