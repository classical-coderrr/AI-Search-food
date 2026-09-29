ALTER TABLE memory_retrieval_traces
    ADD COLUMN used_memory_item_count INT NOT NULL DEFAULT 0;

ALTER TABLE memory_retrieval_traces
    ADD COLUMN used_episode_count INT NOT NULL DEFAULT 0;

-- The trace stores JSON arrays of numeric IDs. Counting separators is portable
-- across the MySQL production database and the H2 MySQL-mode test database.
UPDATE memory_retrieval_traces
SET used_memory_item_count = CASE
        WHEN TRIM(used_memory_item_ids_json) IS NULL OR TRIM(used_memory_item_ids_json) IN ('', '[]') THEN 0
        ELSE LENGTH(used_memory_item_ids_json) - LENGTH(REPLACE(used_memory_item_ids_json, ',', '')) + 1
    END,
    used_episode_count = CASE
        WHEN TRIM(used_episode_ids_json) IS NULL OR TRIM(used_episode_ids_json) IN ('', '[]') THEN 0
        ELSE LENGTH(used_episode_ids_json) - LENGTH(REPLACE(used_episode_ids_json, ',', '')) + 1
    END;
