package com.example.food.recipe;

import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.dto.RecommendationFeedbackResponse;
import com.example.food.recipe.dto.RecommendationReactionRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackServiceTest {

    @Mock
    private RecommendationFeedbackMapper feedbackMapper;

    @Mock
    private SearchLogMapper searchLogMapper;

    @Mock
    private RecipeRecordMapper recipeRecordMapper;

    @Mock
    private MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    private RecommendationFeedbackService service;
    private final AuthPrincipal principal = new AuthPrincipal(7L, "13800138000", AppRole.USER);

    @BeforeEach
    void setUp() {
        service = new RecommendationFeedbackService(
                feedbackMapper,
                searchLogMapper,
                recipeRecordMapper,
                null,
                new ObjectMapper(),
                memoryEpisodeRecorder
        );
    }

    @Test
    void reactionCanBeReplacedAndClearedWithoutCreatingDuplicates() {
        when(searchLogMapper.selectById(11L)).thenReturn(ownedSearchLog(11L, 7L));
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        stubInsert(99L);

        RecommendationFeedbackResponse first = service.setReaction(
                11L,
                new RecommendationReactionRequest("LIKE"),
                principal,
                null
        );

        ArgumentCaptor<RecommendationFeedback> insertCaptor = ArgumentCaptor.forClass(RecommendationFeedback.class);
        verify(feedbackMapper).insert(insertCaptor.capture());
        assertThat(first.reaction()).isEqualTo("LIKE");
        assertThat(insertCaptor.getValue().getSearchLogId()).isEqualTo(11L);

        RecommendationFeedback existing = insertCaptor.getValue();
        when(feedbackMapper.selectOne(any())).thenReturn(existing);
        RecommendationFeedbackResponse replaced = service.setReaction(
                11L,
                new RecommendationReactionRequest("DISLIKE"),
                principal,
                null
        );
        assertThat(replaced.reaction()).isEqualTo("DISLIKE");
        verify(feedbackMapper).updateById(existing);

        RecommendationFeedbackResponse cleared = service.clearReaction(11L, principal, null);
        assertThat(cleared.reaction()).isNull();
        verify(feedbackMapper).deleteById(99L);
        verify(memoryEpisodeRecorder, times(3)).record(any(MemoryBehaviorEpisodeEvent.class));
    }

    @Test
    void cookedIsIdempotentAndPreservesTheFirstCookedTimestamp() {
        when(searchLogMapper.selectById(11L)).thenReturn(ownedSearchLog(11L, 7L));
        RecommendationFeedback existing = new RecommendationFeedback();
        existing.setId(100L);
        existing.setUserId(7L);
        existing.setSearchLogId(11L);
        existing.setCooked(true);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        RecommendationFeedbackResponse response = service.markCooked(11L, principal, null);

        assertThat(response.cooked()).isTrue();
        verify(feedbackMapper, never()).updateById(any(RecommendationFeedback.class));
        verify(feedbackMapper, never()).insert(any(RecommendationFeedback.class));
    }

    @Test
    void anonymousSearchLogCanBeClaimedOnlyWithMatchingAnonymousId() {
        SearchLog anonymousLog = ownedSearchLog(11L, null);
        anonymousLog.setAnonymousId("browser-123");
        when(searchLogMapper.selectById(11L)).thenReturn(anonymousLog);
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        stubInsert(101L);

        RecommendationFeedbackResponse response = service.setReaction(
                11L,
                new RecommendationReactionRequest("LIKE"),
                principal,
                " browser-123 "
        );

        assertThat(response.reaction()).isEqualTo("LIKE");
        assertThat(anonymousLog.getUserId()).isEqualTo(7L);
        verify(searchLogMapper).updateById(anonymousLog);

        when(searchLogMapper.selectById(11L)).thenReturn(ownedSearchLog(11L, null));
        assertThatThrownBy(() -> service.setReaction(
                11L,
                new RecommendationReactionRequest("LIKE"),
                principal,
                "different-browser"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void contextContainsBoundedFeedbackAndUsesRecipeSnapshots() {
        RecommendationFeedback liked = feedback(1L, 11L, "LIKE");
        RecommendationFeedback disliked = feedback(2L, 12L, "DISLIKE");
        RecommendationFeedback cooked = feedback(3L, 13L, null);
        cooked.setCooked(true);
        when(feedbackMapper.selectList(any())).thenReturn(List.of(liked, disliked, cooked));
        when(searchLogMapper.selectById(11L)).thenReturn(snapshot(11L, "番茄炒蛋", "[\"番茄\",\"鸡蛋\"]"));
        when(searchLogMapper.selectById(12L)).thenReturn(snapshot(12L, "香菜拌豆腐", "[\"香菜\",\"豆腐\"]"));
        when(searchLogMapper.selectById(13L)).thenReturn(snapshot(13L, "土豆炖牛肉", "[\"土豆\",\"牛肉\"]"));

        RecommendationFeedbackService.FeedbackContext context = service.context(7L);

        assertThat(context.liked()).contains("番茄炒蛋", "番茄", "鸡蛋");
        assertThat(context.disliked()).contains("香菜拌豆腐", "香菜", "豆腐");
        assertThat(context.cooked()).contains("土豆炖牛肉", "土豆", "牛肉");
        assertThat(context.promptSection()).contains("用户近期推荐反馈", "本次明确输入、忌口和过敏约束");
        assertThat(context.promptSection().length()).isLessThanOrEqualTo(1200);
    }

    @Test
    void contextFallsBackToOwnedSavedRecipeWhenLegacySnapshotIsMissing() {
        RecommendationFeedback cooked = feedback(4L, 14L, null);
        cooked.setCooked(true);
        when(feedbackMapper.selectList(any())).thenReturn(List.of(cooked));
        SearchLog legacyLog = ownedSearchLog(14L, 7L);
        when(searchLogMapper.selectById(14L)).thenReturn(legacyLog);
        RecipeRecord savedRecipe = new RecipeRecord();
        savedRecipe.setId(55L);
        savedRecipe.setUserId(7L);
        savedRecipe.setSearchLogId(14L);
        savedRecipe.setTitle("老菜谱名称");
        when(recipeRecordMapper.findByUserIdAndSearchLogId(7L, 14L)).thenReturn(savedRecipe);

        RecommendationFeedbackService.FeedbackContext context = service.context(7L);

        assertThat(context.cooked()).contains("老菜谱名称");
    }

    private SearchLog ownedSearchLog(Long id, Long userId) {
        SearchLog searchLog = new SearchLog();
        searchLog.setId(id);
        searchLog.setUserId(userId);
        return searchLog;
    }

    private SearchLog snapshot(Long id, String title, String ingredients) {
        SearchLog searchLog = ownedSearchLog(id, 7L);
        searchLog.setResultTitle(title);
        searchLog.setResultIngredients(ingredients);
        return searchLog;
    }

    private RecommendationFeedback feedback(Long id, Long searchLogId, String reaction) {
        RecommendationFeedback feedback = new RecommendationFeedback();
        feedback.setId(id);
        feedback.setUserId(7L);
        feedback.setSearchLogId(searchLogId);
        feedback.setReaction(reaction);
        feedback.setCooked(false);
        return feedback;
    }

    private void stubInsert(Long id) {
        doAnswer(invocation -> {
            RecommendationFeedback feedback = invocation.getArgument(0);
            feedback.setId(id);
            return 1;
        }).when(feedbackMapper).insert(any(RecommendationFeedback.class));
    }
}
