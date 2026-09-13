ALTER TABLE recipe_records ADD COLUMN weekly_menu_plan_id BIGINT NULL;

ALTER TABLE recipe_records
    ADD CONSTRAINT fk_recipe_records_weekly_menu_plan
        FOREIGN KEY (weekly_menu_plan_id) REFERENCES weekly_menu_plans(id) ON DELETE CASCADE;

CREATE INDEX idx_recipe_records_weekly_menu_plan_id
    ON recipe_records(user_id, weekly_menu_plan_id);
