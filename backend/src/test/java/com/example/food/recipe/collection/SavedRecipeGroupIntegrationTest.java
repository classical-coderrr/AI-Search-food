package com.example.food.recipe.collection;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse.Ingredient;
import com.example.food.ai.recipe.dto.RecipeGenerateResponse.Step;
import com.example.food.recipe.RecipeContentIdentity;
import com.example.food.recipe.SavedRecipeIdentityService;
import com.example.food.recipe.SavedRecipeService;
import com.example.food.recipe.collection.dto.*;
import com.example.food.recipe.dto.SaveRecipeRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SavedRecipeGroupIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SavedRecipeService saved;
    @Autowired SavedRecipeIdentityService identities;
    @Autowired SavedRecipeGroupService groups;
    @Autowired SavedRecipeCollectionService collections;

    @Test
    void identicalContentFromDifferentSearchesReusesRecipeAndKeepsEverySource() {
        Long user = user("13900009201");
        Long firstSearch = search(user);
        Long secondSearch = search(user);
        var first = saved.save(request(firstSearch, "番茄炒蛋", "2个"), principal(user), null);
        var second = saved.save(request(secondSearch, " 番茄炒蛋 ", "3个"), principal(user), null);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.alreadySaved()).isFalse();
        assertThat(second.alreadySaved()).isTrue();
        assertThat(second.recipe().ingredients()).contains(new Ingredient("鸡蛋", "2个"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_records WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_saved_sources WHERE user_id = ? AND recipe_id = ?", Integer.class, user, first.id())).isEqualTo(2);
        assertThat(saved.save(request(secondSearch, "番茄炒蛋", "2个"), principal(user), null).id()).isEqualTo(first.id());
    }

    @Test
    void sameDishDoesNotCreateVersionsAndPaginationCountsDishes() {
        Long user = user("13900009202");
        var first = saved.save(request(search(user), "番茄炒蛋", "2个"), principal(user), null);
        saved.save(request(search(user), "番茄炒蛋", "3个"), principal(user), null);
        saved.save(request(search(user), "番茄蛋汤", "2个"), principal(user), null);
        var custom = collections.createCollection(user, new RecipeCollectionRequest("早餐"));
        collections.moveRecipe(user, first.id(), new SavedRecipeCollectionRequest(custom.id()));
        collections.replaceTags(user, first.id(), new SavedRecipeTagsRequest(List.of("家常")));
        var filtered = groups.list(user, custom.id(), null, "dinner", "balanced", "家常", "savedAtDesc", 1, 20);
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items()).singleElement().satisfies(item -> {
            assertThat(item.versionCount()).isEqualTo(1);
            assertThat(item.id()).isEqualTo(first.id());
        });
        String key = RecipeContentIdentity.groupKey("番茄炒蛋");
        assertThat(groups.versions(user, key, first.id())).hasSize(1)
                .extracting(SavedRecipeResponse::id).contains(first.id());
        var page = groups.list(user, null, null, null, null, null, "savedAtDesc", 1, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(groups.list(user, null, null, null, null, null, "savedAtDesc", 2, 1).items()).hasSize(1);
    }

    @Test
    void legacyDuplicatesRemainAddressableWhileGroupsHideThemAndManageAllRows() {
        Long user = user("13900009203");
        var first = saved.save(request(search(user), "番茄炒蛋", "2个"), principal(user), null);
        Long variantId = legacyCopy(user, first.id());
        jdbc.update("UPDATE recipe_ingredients SET amount = '3个' WHERE recipe_id = ? AND ingredient_name = '鸡蛋'", variantId);
        Long legacySearch = search(user);
        jdbc.update("INSERT INTO recipe_records(user_id, search_log_id, title, summary, raw_response, created_at) SELECT user_id, ?, title, summary, raw_response, ? FROM recipe_records WHERE id = ?",
                legacySearch, LocalDateTime.now().plusSeconds(1), first.id());
        Long duplicateId = jdbc.queryForObject("SELECT MAX(id) FROM recipe_records WHERE user_id = ?", Long.class, user);
        jdbc.update("INSERT INTO recipe_ingredients(recipe_id, ingredient_name, amount) SELECT ?, ingredient_name, amount FROM recipe_ingredients WHERE recipe_id = ?", duplicateId, first.id());
        jdbc.update("INSERT INTO recipe_steps(recipe_id, step_no, title, instruction, estimated_minutes) SELECT ?, step_no, title, instruction, estimated_minutes FROM recipe_steps WHERE recipe_id = ?", duplicateId, first.id());
        jdbc.update("INSERT INTO recipe_shares(user_id, recipe_id, token, validity_type) VALUES (?, ?, ?, 'PERMANENT')", user, duplicateId, "a".repeat(64));
        String key = RecipeContentIdentity.groupKey("番茄炒蛋");
        identities.indexLegacyUser(user);
        identities.indexLegacyUser(user);
        assertThat(groups.list(user, null, null, null, null, null, "savedAtDesc", 1, 20).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.versionCount()).isEqualTo(1);
                    assertThat(item.id()).isEqualTo(first.id());
                });
        assertThat(groups.versions(user, key, duplicateId)).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(first.id()));
        assertThat(saved.detail(user, first.id()).recipe()).isNotNull();
        assertThat(saved.detail(user, duplicateId).recipe()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT recipe_id FROM recipe_shares WHERE token = ?", Long.class, "a".repeat(64))).isEqualTo(duplicateId);
        var folder = collections.createCollection(user, new RecipeCollectionRequest("历史菜谱"));
        assertThat(groups.batchMove(user, List.of(key), folder.id()).successCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_collection_items WHERE user_id = ? AND collection_id = ?", Integer.class, user, folder.id())).isEqualTo(3);
        assertThat(groups.batchTags(user, List.of(key), List.of("家常"), List.of()).successCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_record_tags WHERE user_id = ?", Integer.class, user)).isEqualTo(3);
        assertThat(groups.listCollections(user)).filteredOn(item -> item.id().equals(folder.id())).singleElement()
                .satisfies(item -> assertThat(item.recipeCount()).isEqualTo(1));
        assertThat(groups.batchDelete(user, List.of(key)).successCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_records WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(saved.save(request(search(user), "番茄炒蛋", "4个"), principal(user), null).alreadySaved()).isFalse();
    }

    @Test
    void anotherUserCannotReadOrMutateAGroup() {
        Long user = user("13900009204");
        Long other = user("13900009205");
        var recipe = saved.save(request(search(user), "番茄炒蛋", "2个"), principal(user), null);
        assertThat(saved.findSavedByTitle(other, "番茄炒蛋")).isNull();
        String key = RecipeContentIdentity.groupKey("番茄炒蛋");
        assertThatThrownBy(() -> groups.versions(other, key, recipe.id())).hasMessageContaining("菜谱组不存在");
        assertThat(groups.batchDelete(other, List.of(key)).failureCount()).isEqualTo(1);
        assertThat(saved.detail(user, recipe.id())).isNotNull();
        assertThat(saved.save(request(search(other), "番茄炒蛋", "2个"), principal(other), null).id()).isNotEqualTo(recipe.id());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSavesFromDifferentSearchesProduceOneRecipe() throws Exception {
        Long user = user("13900009206");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> requests = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                Long log = search(user);
                String amount = (index + 2) + "个";
                requests.add(executor.submit(() -> {
                    start.await();
                    return saved.save(request(log, "番茄炒蛋", amount), principal(user), null).id();
                }));
            }
            start.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> result : requests) results.add(result.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().distinct().toList()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_records WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_episodes WHERE user_id = ? AND episode_type = 'RECIPE_SAVED'", Integer.class, user)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM recipe_records WHERE user_id = ?", user);
            jdbc.update("DELETE FROM search_logs WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    private SaveRecipeRequest request(Long search, String title, String eggs) {
        return new SaveRecipeRequest(search, title, "家常做法", List.of(),
                List.of(new Ingredient("番茄", "200克"), new Ingredient("鸡蛋", eggs)),
                List.of(new Step(1, "炒制", "先炒鸡蛋，再加入番茄炒熟", 10)), List.of(), List.of(), "qwen", "test");
    }
    private Long legacyCopy(Long user, Long recipeId) {
        jdbc.update("INSERT INTO recipe_records(user_id, search_log_id, title, summary, raw_response, created_at) SELECT user_id, ?, title, summary, raw_response, ? FROM recipe_records WHERE id = ?",
                search(user), LocalDateTime.now(), recipeId);
        Long duplicate = jdbc.queryForObject("SELECT MAX(id) FROM recipe_records WHERE user_id = ?", Long.class, user);
        jdbc.update("INSERT INTO recipe_ingredients(recipe_id, ingredient_name, amount) SELECT ?, ingredient_name, amount FROM recipe_ingredients WHERE recipe_id = ?", duplicate, recipeId);
        jdbc.update("INSERT INTO recipe_steps(recipe_id, step_no, title, instruction, estimated_minutes) SELECT ?, step_no, title, instruction, estimated_minutes FROM recipe_steps WHERE recipe_id = ?", duplicate, recipeId);
        return duplicate;
    }
    private AuthPrincipal principal(Long user) { return new AuthPrincipal(user, "13900009200", AppRole.USER); }
    private Long user(String phone) {
        jdbc.update("INSERT INTO users(phone, nickname, role, enabled) VALUES (?, '菜谱归组测试', 'USER', 1)", phone);
        return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
    private Long search(Long user) {
        jdbc.update("INSERT INTO search_logs(user_id, query_text, input_type, meal_type, goal) VALUES (?, '番茄、鸡蛋', 'text', 'dinner', 'balanced')", user);
        return jdbc.queryForObject("SELECT MAX(id) FROM search_logs WHERE user_id = ?", Long.class, user);
    }
}
