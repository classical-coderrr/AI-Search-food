ALTER TABLE recipe_records ADD COLUMN dish_group_key CHAR(64);
ALTER TABLE recipe_records ADD COLUMN content_fingerprint CHAR(64);
CREATE INDEX idx_recipe_records_dish_group ON recipe_records(user_id, dish_group_key, created_at, id);
CREATE INDEX idx_recipe_records_content ON recipe_records(user_id, content_fingerprint);

CREATE TABLE recipe_content_identities (
    user_id BIGINT NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    recipe_id BIGINT NULL,
    PRIMARY KEY (user_id, content_fingerprint),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (recipe_id) REFERENCES recipe_records(id) ON DELETE SET NULL
);

CREATE TABLE recipe_saved_sources (
    user_id BIGINT NOT NULL,
    search_log_id BIGINT NOT NULL,
    recipe_id BIGINT NULL,
    PRIMARY KEY (user_id, search_log_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (search_log_id) REFERENCES search_logs(id) ON DELETE CASCADE,
    FOREIGN KEY (recipe_id) REFERENCES recipe_records(id) ON DELETE SET NULL
);
