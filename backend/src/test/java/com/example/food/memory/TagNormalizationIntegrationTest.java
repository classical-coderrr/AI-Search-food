package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TagNormalizationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TagNormalizationService normalizationService;

    @Autowired
    private MemoryEpisodeService episodeService;

    @Autowired
    private MemoryCandidateService candidateService;

    @Autowired
    private MemoryConsolidationService consolidationService;

    @Test
    void normalizesChineseAndEnglishIngredientAliasesToOneCanonicalTag() {
        TagNormalizationResult shortName = normalizationService.normalize("INGREDIENT_PREFERENCE", "鸡胸");
        TagNormalizationResult fullName = normalizationService.normalize("INGREDIENT_PREFERENCE", "鸡胸肉");
        TagNormalizationResult englishName = normalizationService.normalize("INGREDIENT_PREFERENCE", "Chicken Breast");

        assertThat(shortName.canonicalId()).isEqualTo("INGREDIENT_CHICKEN_BREAST");
        assertThat(fullName.canonicalId()).isEqualTo(shortName.canonicalId());
        assertThat(englishName.canonicalId()).isEqualTo(shortName.canonicalId());
        assertThat(shortName.canonicalName()).isEqualTo("鸡胸肉");
        assertThat(shortName.confidence()).isEqualByComparingTo("1.0000");
        assertThat(shortName.source()).isEqualTo("ALIAS");
    }

    @Test
    void mergesParentAndSpecificIngredientMemoriesBySemanticGroup() {
        Long userId = insertUser("13900000901", "标签归一化用户");
        saveIngredientEpisode(userId, "鸡肉", "semantic-chicken");
        saveIngredientEpisode(userId, "鸡胸肉", "semantic-chicken-breast");

        List<MemoryCandidate> candidates = candidateService.listOwned(
                userId, null, "INGREDIENT_PREFERENCE", 20);
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(MemoryCandidate::getCanonicalId)
                .containsExactlyInAnyOrder("INGREDIENT_CHICKEN", "INGREDIENT_CHICKEN_BREAST");
        assertThat(candidates).extracting(MemoryCandidate::getCanonicalGroupId)
                .containsOnly("INGREDIENT_CHICKEN");

        consolidationService.consolidate(userId);

        List<MemoryItem> items = consolidationService.listOwnedItems(userId, 20).stream()
                .filter(item -> "INGREDIENT_PREFERENCE".equals(item.getMemoryType()))
                .toList();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getCanonicalGroupId()).isEqualTo("INGREDIENT_CHICKEN");
        assertThat(items.get(0).getOccurrenceCount()).isEqualTo(2);
        assertThat(items.get(0).getCanonicalTagId()).isNotNull();
    }

    @Test
    void keepsUnknownEntityTraceableWithoutInventingCanonicalTag() {
        TagNormalizationResult result = normalizationService.normalize(
                "INGREDIENT_PREFERENCE", "用户自定义食材");

        assertThat(result.mapped()).isFalse();
        assertThat(result.canonicalId()).isNull();
        assertThat(result.canonicalName()).isEqualTo("用户自定义食材");
        assertThat(result.source()).isEqualTo("UNMAPPED");
    }

    private void saveIngredientEpisode(Long userId, String ingredient, String key) {
        MemoryEpisode episode = episodeService.record(userId, new MemoryEpisodeCommand(
                null,
                null,
                "RECIPE_SAVED",
                "RECIPE_RECORD",
                key,
                key,
                key,
                "收藏含有" + ingredient + "的菜谱",
                "{\"recipeTitle\":\"测试菜谱\",\"ingredients\":[\"" + ingredient + "\"]}",
                null,
                null
        )).episode();
        candidateService.extractAndPersist(userId, episode.getId());
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
