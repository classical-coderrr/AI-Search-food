package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryRetrievalIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryEpisodeService episodeService;
    @Autowired private MemoryRetriever retriever;
    @Autowired private MemorySessionService sessionService;

    @Test
    void retrievesOnlyOwnedEpisodesAndAppliesExplicitMetadataFilters() {
        Long userId = insertUser("13900000801", "记忆检索用户");
        Long otherUserId = insertUser("13900000802", "隔离验证用户");
        MemoryEpisode owned = saveEpisode(userId, "workout-dinner", "训练后收藏黑椒鸡胸肉饭",
                "{\"scene\":\"POST_WORKOUT_DINNER\",\"mealType\":\"DINNER\","
                        + "\"ingredients\":[\"鸡胸肉\"],\"rating\":5}");
        saveEpisode(userId, "lunch", "午餐收藏番茄意面",
                "{\"scene\":\"WEEKDAY_LUNCH\",\"mealType\":\"LUNCH\",\"ingredients\":[\"番茄\"]}");
        saveEpisode(otherUserId, "other-user", "训练后收藏鸡胸肉",
                "{\"scene\":\"POST_WORKOUT_DINNER\",\"mealType\":\"DINNER\","
                        + "\"ingredients\":[\"鸡胸肉\"]}");

        MemorySearchCommand command = new MemorySearchCommand("鸡胸肉", null, null,
                List.of("RECIPE_SAVED"), List.of("POST_WORKOUT"), List.of("DINNER"),
                null, null, List.of("鸡胸肉"), null, null, BigDecimal.valueOf(0.4), 10);
        MemoryRetrievalResult result = retriever.search(userId, command);

        assertThat(result.hits()).extracting(MemorySearchHit::id).containsExactly(owned.getId());
        assertThat(result.trace().userId()).isEqualTo(userId);
        assertThat(result.trace().selectedEpisodeIds()).containsExactly(owned.getId());
        assertThat(result.hits().get(0).scores().total()).isBetween(0.0, 1.0);
    }

    @Test
    void refusesToUseAnotherUsersSessionInRetrieval() {
        Long userId = insertUser("13900000803", "当前用户");
        Long otherUserId = insertUser("13900000804", "另一个用户");
        MemorySession otherSession = sessionService.open(otherUserId,
                new MemorySessionOpenCommand(null, "other-session", "菜谱推荐", null,
                        null, null, null, null, null));

        assertThatThrownBy(() -> retriever.search(userId,
                new MemorySearchCommand("菜谱", otherSession.getId(), null, null, null, null,
                        null, null, null, null, null, null, 5)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private MemoryEpisode saveEpisode(Long userId, String key, String summary, String payload) {
        return episodeService.record(userId, new MemoryEpisodeCommand(null, null,
                "RECIPE_SAVED", "RECIPE_RECORD", key, key, key, summary, payload,
                null, BigDecimal.valueOf(0.8))).episode();
    }

    private Long insertUser(String phone, String nickname) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, nickname);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
