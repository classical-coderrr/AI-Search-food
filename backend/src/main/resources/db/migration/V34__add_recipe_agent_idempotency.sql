ALTER TABLE recipe_records
    ADD COLUMN agent_idempotency_key VARCHAR(96) NULL;

CREATE UNIQUE INDEX uq_recipe_records_user_agent_key
    ON recipe_records(user_id, agent_idempotency_key);
