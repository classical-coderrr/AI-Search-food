CREATE TABLE canonical_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    canonical_id VARCHAR(128) NOT NULL,
    canonical_name VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    parent_canonical_id VARCHAR(128) NULL,
    merge_canonical_id VARCHAR(128) NOT NULL,
    aliases_json TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_canonical_tags_id
    ON canonical_tags(canonical_id);
CREATE INDEX idx_canonical_tags_category
    ON canonical_tags(category, enabled, id);
CREATE INDEX idx_canonical_tags_merge
    ON canonical_tags(merge_canonical_id, enabled, id);

CREATE TABLE canonical_tag_aliases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    canonical_tag_id BIGINT NOT NULL,
    alias VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    alias_type VARCHAR(32) NOT NULL DEFAULT 'ALIAS',
    source VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_canonical_tag_aliases_tag
        FOREIGN KEY (canonical_tag_id) REFERENCES canonical_tags(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_canonical_tag_aliases_key
    ON canonical_tag_aliases(canonical_tag_id, normalized_alias);
CREATE INDEX idx_canonical_tag_aliases_lookup
    ON canonical_tag_aliases(normalized_alias, enabled, canonical_tag_id);

ALTER TABLE memory_candidates ADD COLUMN canonical_tag_id BIGINT NULL;
ALTER TABLE memory_candidates ADD COLUMN canonical_id VARCHAR(128) NULL;
ALTER TABLE memory_candidates ADD COLUMN canonical_entity VARCHAR(255) NULL;
ALTER TABLE memory_candidates ADD COLUMN canonical_category VARCHAR(64) NULL;
ALTER TABLE memory_candidates ADD COLUMN canonical_group_id VARCHAR(128) NULL;
ALTER TABLE memory_candidates ADD COLUMN normalization_confidence DECIMAL(5,4) NULL;
ALTER TABLE memory_candidates ADD COLUMN normalization_source VARCHAR(32) NULL;

ALTER TABLE memory_candidates
    ADD CONSTRAINT fk_memory_candidates_canonical_tag
        FOREIGN KEY (canonical_tag_id) REFERENCES canonical_tags(id) ON DELETE SET NULL;

CREATE INDEX idx_memory_candidates_user_canonical
    ON memory_candidates(user_id, canonical_group_id, preference, id DESC);

ALTER TABLE memory_items ADD COLUMN canonical_tag_id BIGINT NULL;
ALTER TABLE memory_items ADD COLUMN canonical_id VARCHAR(128) NULL;
ALTER TABLE memory_items ADD COLUMN canonical_category VARCHAR(64) NULL;
ALTER TABLE memory_items ADD COLUMN canonical_group_id VARCHAR(128) NULL;

ALTER TABLE memory_items
    ADD CONSTRAINT fk_memory_items_canonical_tag
        FOREIGN KEY (canonical_tag_id) REFERENCES canonical_tags(id) ON DELETE SET NULL;

CREATE INDEX idx_memory_items_user_canonical
    ON memory_items(user_id, canonical_group_id, preference, id DESC);

INSERT INTO canonical_tags
    (canonical_id, canonical_name, category, parent_canonical_id, merge_canonical_id, aliases_json)
VALUES
    ('INGREDIENT_CHICKEN', '鸡肉', 'INGREDIENT', NULL, 'INGREDIENT_CHICKEN', '["鸡肉","chicken"]'),
    ('INGREDIENT_CHICKEN_BREAST', '鸡胸肉', 'INGREDIENT', 'INGREDIENT_CHICKEN', 'INGREDIENT_CHICKEN', '["鸡胸","鸡胸肉","鸡肉胸","chicken breast"]'),
    ('INGREDIENT_TOMATO', '番茄', 'INGREDIENT', NULL, 'INGREDIENT_TOMATO', '["番茄","西红柿","tomato"]'),
    ('INGREDIENT_EGG', '鸡蛋', 'INGREDIENT', NULL, 'INGREDIENT_EGG', '["鸡蛋","蛋","egg"]'),
    ('INGREDIENT_BROCCOLI', '西兰花', 'INGREDIENT', NULL, 'INGREDIENT_BROCCOLI', '["西兰花","broccoli"]'),
    ('CUISINE_SICHUAN', '川菜', 'CUISINE', NULL, 'CUISINE_SICHUAN', '["川菜","四川菜","sichuan cuisine"]'),
    ('TASTE_MILD', '清淡', 'TASTE', NULL, 'TASTE_MILD', '["清淡","light"]'),
    ('TASTE_SPICY', '辣', 'TASTE', NULL, 'TASTE_SPICY', '["辣","spicy"]'),
    ('COOKING_METHOD_PAN_FRY', '煎', 'COOKING_METHOD', NULL, 'COOKING_METHOD_PAN_FRY', '["煎","香煎","锅煎","pan fry"]'),
    ('DIET_GOAL_MUSCLE_GAIN', '增肌', 'DIET_GOAL', NULL, 'DIET_GOAL_MUSCLE_GAIN', '["增肌","肌肉增长","健身增肌","muscle gain"]'),
    ('MEAL_TYPE_DINNER', '晚餐', 'MEAL_TYPE', NULL, 'MEAL_TYPE_DINNER', '["晚餐","dinner"]'),
    ('SCENE_POST_WORKOUT', '训练后', 'SCENE', NULL, 'SCENE_POST_WORKOUT', '["训练后","健身后","post workout"]'),
    ('COOKING_DIFFICULTY_BEGINNER', '入门', 'COOKING_DIFFICULTY', NULL, 'COOKING_DIFFICULTY_BEGINNER', '["入门","新手","beginner"]');

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT id, '鸡肉', '鸡肉', 'zh-CN', 'CANONICAL', 'SYSTEM', 1.0000
FROM canonical_tags WHERE canonical_id = 'INGREDIENT_CHICKEN';
INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT id, 'chicken', 'chicken', 'en', 'ALIAS', 'SYSTEM', 1.0000
FROM canonical_tags WHERE canonical_id = 'INGREDIENT_CHICKEN';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT id, alias, normalized_alias, locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '鸡胸' AS alias, '鸡胸' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '鸡胸肉', '鸡胸肉', 'zh-CN'
    UNION ALL SELECT '鸡肉胸', '鸡肉胸', 'zh-CN'
    UNION ALL SELECT 'chicken breast', 'chicken breast', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'INGREDIENT_CHICKEN_BREAST';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '番茄' AS alias, '番茄' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '西红柿', '西红柿', 'zh-CN'
    UNION ALL SELECT 'tomato', 'tomato', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'INGREDIENT_TOMATO';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '鸡蛋' AS alias, '鸡蛋' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '蛋', '蛋', 'zh-CN'
    UNION ALL SELECT 'egg', 'egg', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'INGREDIENT_EGG';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '西兰花' AS alias, '西兰花' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT 'broccoli', 'broccoli', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'INGREDIENT_BROCCOLI';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '川菜' AS alias, '川菜' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '四川菜', '四川菜', 'zh-CN'
    UNION ALL SELECT 'sichuan cuisine', 'sichuan cuisine', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'CUISINE_SICHUAN';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '清淡' AS alias, '清淡' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT 'light', 'light', 'en'
    UNION ALL SELECT '辣', '辣', 'zh-CN'
    UNION ALL SELECT 'spicy', 'spicy', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = CASE
    WHEN aliases.normalized_alias IN ('清淡', 'light') THEN 'TASTE_MILD'
    ELSE 'TASTE_SPICY'
END;

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '煎' AS alias, '煎' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '香煎', '香煎', 'zh-CN'
    UNION ALL SELECT '锅煎', '锅煎', 'zh-CN'
    UNION ALL SELECT 'pan fry', 'pan fry', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'COOKING_METHOD_PAN_FRY';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '增肌' AS alias, '增肌' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '肌肉增长', '肌肉增长', 'zh-CN'
    UNION ALL SELECT '健身增肌', '健身增肌', 'zh-CN'
    UNION ALL SELECT 'muscle gain', 'muscle gain', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'DIET_GOAL_MUSCLE_GAIN';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '晚餐' AS alias, '晚餐' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT 'dinner', 'dinner', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'MEAL_TYPE_DINNER';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '训练后' AS alias, '训练后' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '健身后', '健身后', 'zh-CN'
    UNION ALL SELECT 'post workout', 'post workout', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'SCENE_POST_WORKOUT';

INSERT INTO canonical_tag_aliases
    (canonical_tag_id, alias, normalized_alias, locale, alias_type, source, confidence)
SELECT tag.id, aliases.alias, aliases.normalized_alias, aliases.locale, 'ALIAS', 'SYSTEM', 1.0000
FROM (
    SELECT '入门' AS alias, '入门' AS normalized_alias, 'zh-CN' AS locale
    UNION ALL SELECT '新手', '新手', 'zh-CN'
    UNION ALL SELECT 'beginner', 'beginner', 'en'
) aliases
JOIN canonical_tags tag ON tag.canonical_id = 'COOKING_DIFFICULTY_BEGINNER';
