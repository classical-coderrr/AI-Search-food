CREATE TABLE recipe_dish_identities (
    user_id BIGINT NOT NULL,
    dish_group_key CHAR(64) NOT NULL,
    recipe_id BIGINT NULL,
    PRIMARY KEY (user_id, dish_group_key),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (recipe_id) REFERENCES recipe_records(id) ON DELETE SET NULL
);

INSERT INTO recipe_dish_identities(user_id, dish_group_key, recipe_id)
SELECT user_id, dish_group_key, MIN(id) FROM recipe_records
WHERE user_id IS NOT NULL AND dish_group_key IS NOT NULL AND weekly_menu_plan_id IS NULL
GROUP BY user_id, dish_group_key;
