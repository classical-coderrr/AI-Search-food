package com.example.food.recipe;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.dto.RecommendationFeedbackResponse;
import com.example.food.recipe.dto.RecommendationReactionRequest;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RecommendationFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedbackService.class);
    private static final int FEEDBACK_LOOKBACK_DAYS = 90;
    private static final int MAX_FEEDBACK_RECORDS = 30;
    private static final int MAX_CONTEXT_ITEMS = 5;
    private static final int MAX_CONTEXT_ITEM_LENGTH = 64;
    private static final int MAX_CONTEXT_LENGTH = 1200;

    private final RecommendationFeedbackMapper feedbackMapper;
    private final SearchLogMapper searchLogMapper;
    private final RecipeRecordMapper recipeRecordMapper;
    private final RecipeIngredientMapper recipeIngredientMapper;
    private final ObjectMapper objectMapper;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    @Autowired
    public RecommendationFeedbackService(
            RecommendationFeedbackMapper feedbackMapper,
            SearchLogMapper searchLogMapper,
            RecipeRecordMapper recipeRecordMapper,
            RecipeIngredientMapper recipeIngredientMapper,
            ObjectMapper objectMapper,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this.feedbackMapper = feedbackMapper;
        this.searchLogMapper = searchLogMapper;
        this.recipeRecordMapper = recipeRecordMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.objectMapper = objectMapper;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
    }

    public RecommendationFeedbackService(
            RecommendationFeedbackMapper feedbackMapper,
            SearchLogMapper searchLogMapper,
            RecipeRecordMapper recipeRecordMapper,
            ObjectMapper objectMapper
    ) {
        this(feedbackMapper, searchLogMapper, recipeRecordMapper, null, objectMapper, null);
    }

    public RecommendationFeedbackResponse get(
            Long searchLogId,
            AuthPrincipal principal,
            String anonymousId
    ) {
        requireUser(principal);
        requireOwnedSearchLog(searchLogId, principal, anonymousId, false);
        RecommendationFeedback feedback = find(principal.id(), searchLogId);
        return feedback == null ? RecommendationFeedbackResponse.empty(searchLogId) : toResponse(feedback);
    }

    @Transactional
    public RecommendationFeedbackResponse setReaction(
            Long searchLogId,
            RecommendationReactionRequest request,
            AuthPrincipal principal,
            String anonymousId
    ) {
        requireUser(principal);
        SearchLog searchLog = requireOwnedSearchLog(searchLogId, principal, anonymousId, true);
        String reaction = normalizeReaction(request == null ? null : request.reaction());
        RecommendationFeedback feedback = find(principal.id(), searchLogId);
        LocalDateTime now = LocalDateTime.now();
        if (feedback == null) {
            feedback = new RecommendationFeedback();
            feedback.setUserId(principal.id());
            feedback.setSearchLogId(searchLogId);
            feedback.setCooked(false);
            feedback.setCreatedAt(now);
            feedback.setUpdatedAt(now);
            feedback.setReaction(reaction);
            feedback.setReactedAt(now);
            feedbackMapper.insert(feedback);
            recordFeedbackEpisode(principal.id(), searchLog, feedback, "REACTION", reaction, now, new BigDecimal("0.7500"));
            return toResponse(feedback);
        }
        if (!reaction.equals(feedback.getReaction())) {
            feedback.setReaction(reaction);
            feedback.setReactedAt(now);
            feedback.setUpdatedAt(now);
            feedbackMapper.updateById(feedback);
            recordFeedbackEpisode(principal.id(), searchLog, feedback, "REACTION", reaction, now, new BigDecimal("0.7500"));
        }
        return toResponse(feedback);
    }

    @Transactional
    public RecommendationFeedbackResponse clearReaction(
            Long searchLogId,
            AuthPrincipal principal,
            String anonymousId
    ) {
        requireUser(principal);
        SearchLog searchLog = requireOwnedSearchLog(searchLogId, principal, anonymousId, true);
        RecommendationFeedback feedback = find(principal.id(), searchLogId);
        if (feedback == null) {
            return RecommendationFeedbackResponse.empty(searchLogId);
        }
        String previousReaction = feedback.getReaction();
        feedback.setReaction(null);
        feedback.setReactedAt(null);
        if (!Boolean.TRUE.equals(feedback.getCooked())) {
            feedbackMapper.deleteById(feedback.getId());
            if (previousReaction != null) {
                recordFeedbackEpisode(principal.id(), searchLog, feedback, "REACTION_CLEARED", previousReaction,
                        LocalDateTime.now(), new BigDecimal("0.5500"));
            }
            return RecommendationFeedbackResponse.empty(searchLogId);
        }
        LocalDateTime now = LocalDateTime.now();
        feedback.setUpdatedAt(now);
        feedbackMapper.updateById(feedback);
        if (previousReaction != null) {
            recordFeedbackEpisode(principal.id(), searchLog, feedback, "REACTION_CLEARED", previousReaction,
                    now, new BigDecimal("0.5500"));
        }
        return toResponse(feedback);
    }

    @Transactional
    public RecommendationFeedbackResponse markCooked(
            Long searchLogId,
            AuthPrincipal principal,
            String anonymousId
    ) {
        requireUser(principal);
        SearchLog searchLog = requireOwnedSearchLog(searchLogId, principal, anonymousId, true);
        RecommendationFeedback feedback = find(principal.id(), searchLogId);
        LocalDateTime now = LocalDateTime.now();
        if (feedback == null) {
            feedback = new RecommendationFeedback();
            feedback.setUserId(principal.id());
            feedback.setSearchLogId(searchLogId);
            feedback.setCooked(true);
            feedback.setCookedAt(now);
            feedback.setCreatedAt(now);
            feedback.setUpdatedAt(now);
            feedbackMapper.insert(feedback);
            recordFeedbackEpisode(principal.id(), searchLog, feedback, "COOKED", null, now, new BigDecimal("0.8500"));
            return toResponse(feedback);
        }
        if (!Boolean.TRUE.equals(feedback.getCooked())) {
            feedback.setCooked(true);
            feedback.setCookedAt(now);
            feedback.setUpdatedAt(now);
            feedbackMapper.updateById(feedback);
            recordFeedbackEpisode(principal.id(), searchLog, feedback, "COOKED", null, now, new BigDecimal("0.8500"));
        }
        return toResponse(feedback);
    }

    /**
     * Builds a bounded, sanitized context for the next recipe prompt.
     * Feedback failures are intentionally handled by the caller so generation can degrade safely.
     */
    public FeedbackContext context(Long userId) {
        if (userId == null) {
            return FeedbackContext.empty();
        }
        LocalDateTime since = LocalDateTime.now().minusDays(FEEDBACK_LOOKBACK_DAYS);
        List<RecommendationFeedback> feedbacks = feedbackMapper.selectList(new QueryWrapper<RecommendationFeedback>()
                .eq("user_id", userId)
                .ge("updated_at", since)
                .and(wrapper -> wrapper
                        .isNotNull("reaction")
                        .or()
                        .eq("cooked", true))
                .orderByDesc("updated_at")
                .last("LIMIT " + MAX_FEEDBACK_RECORDS));

        LinkedHashSet<String> liked = new LinkedHashSet<>();
        LinkedHashSet<String> disliked = new LinkedHashSet<>();
        LinkedHashSet<String> cooked = new LinkedHashSet<>();
        for (RecommendationFeedback feedback : feedbacks) {
            SearchLog searchLog = searchLogMapper.selectById(feedback.getSearchLogId());
            if (searchLog == null) {
                continue;
            }
            List<String> descriptors = descriptors(searchLog, userId);
            if ("LIKE".equals(feedback.getReaction())) {
                addBounded(liked, descriptors);
            } else if ("DISLIKE".equals(feedback.getReaction())) {
                addBounded(disliked, descriptors);
            }
            if (Boolean.TRUE.equals(feedback.getCooked())) {
                addBounded(cooked, descriptors);
            }
        }
        return new FeedbackContext(
                boundedList(liked),
                boundedList(disliked),
                boundedList(cooked)
        );
    }

    private RecommendationFeedback find(Long userId, Long searchLogId) {
        return feedbackMapper.selectOne(new QueryWrapper<RecommendationFeedback>()
                .eq("user_id", userId)
                .eq("search_log_id", searchLogId));
    }

    private SearchLog requireOwnedSearchLog(
            Long searchLogId,
            AuthPrincipal principal,
            String anonymousId,
            boolean claimAnonymousLog
    ) {
        if (searchLogId == null || searchLogId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索记录编号不合法");
        }
        SearchLog searchLog = searchLogMapper.selectById(searchLogId);
        if (searchLog == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "搜索记录不存在");
        }
        if (principal.id().equals(searchLog.getUserId())) {
            return searchLog;
        }
        boolean anonymousMatch = searchLog.getUserId() == null
                && StringUtils.hasText(anonymousId)
                && anonymousId.trim().equals(searchLog.getAnonymousId());
        boolean savedRecipeMatch = searchLog.getUserId() == null
                && recipeRecordMapper.existsByUserIdAndSearchLogId(principal.id(), searchLogId);
        if (!anonymousMatch && !savedRecipeMatch) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该推荐记录");
        }
        if (claimAnonymousLog) {
            searchLog.setUserId(principal.id());
            searchLogMapper.updateById(searchLog);
        }
        return searchLog;
    }

    private void requireUser(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (principal.role() != AppRole.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅普通用户可以提交推荐反馈");
        }
    }

    private String normalizeReaction(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"LIKE".equals(normalized) && !"DISLIKE".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈类型仅支持 LIKE 或 DISLIKE");
        }
        return normalized;
    }

    private RecommendationFeedbackResponse toResponse(RecommendationFeedback feedback) {
        return new RecommendationFeedbackResponse(
                feedback.getSearchLogId(),
                feedback.getReaction(),
                Boolean.TRUE.equals(feedback.getCooked()),
                feedback.getReactedAt(),
                feedback.getCookedAt()
        );
    }

    private void recordFeedbackEpisode(
            Long userId,
            SearchLog searchLog,
            RecommendationFeedback feedback,
            String action,
            String reaction,
            LocalDateTime occurredAt,
            BigDecimal importance
    ) {
        if (memoryEpisodeRecorder == null || searchLog == null || feedback == null) {
            return;
        }
        String feedbackId = String.valueOf(feedback.getId());
        String eventKey = "feedback:" + feedbackId + ":" + action + ":" + occurredAt;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("reaction", reaction);
        payload.put("cooked", Boolean.TRUE.equals(feedback.getCooked()));
        payload.put("searchLogId", searchLog.getId());
        payload.put("recipeTitle", searchLog.getResultTitle());
        payload.put("ingredients", descriptors(searchLog, userId));
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId,
                null,
                null,
                "RECIPE_FEEDBACK",
                "RECOMMENDATION_FEEDBACK",
                feedbackId,
                eventKey,
                eventKey,
                action + "：" + (searchLog.getResultTitle() == null ? searchLog.getId() : searchLog.getResultTitle()),
                payload,
                occurredAt,
                importance
        ));
    }

    private List<String> descriptors(SearchLog searchLog, Long userId) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (StringUtils.hasText(searchLog.getResultTitle())) {
            values.add(searchLog.getResultTitle());
        }
        if (StringUtils.hasText(searchLog.getResultIngredients())) {
            try {
                values.addAll(objectMapper.readValue(
                        searchLog.getResultIngredients(),
                        new TypeReference<List<String>>() { }
                ));
            } catch (Exception exception) {
                log.debug("推荐反馈食材快照解析失败，searchLogId={}", searchLog.getId(), exception);
            }
        }
        if (values.isEmpty() && recipeRecordMapper != null) {
            RecipeRecord recipeRecord = recipeRecordMapper.findByUserIdAndSearchLogId(userId, searchLog.getId());
            if (recipeRecord != null) {
                values.add(recipeRecord.getTitle());
                if (recipeIngredientMapper != null) {
                    recipeIngredientMapper.selectList(new QueryWrapper<RecipeIngredient>()
                                    .eq("recipe_id", recipeRecord.getId())
                                    .orderByAsc("id"))
                            .stream()
                            .map(RecipeIngredient::getIngredientName)
                            .forEach(values::add);
                }
            }
        }
        if (values.isEmpty() && StringUtils.hasText(searchLog.getQueryText())) {
            values.addAll(List.of(searchLog.getQueryText().split("[,，、;；\\r\\n]+")));
        }
        return values.stream()
                .map(this::sanitize)
                .filter(StringUtils::hasText)
                .limit(MAX_CONTEXT_ITEM_LENGTH)
                .toList();
    }

    private void addBounded(LinkedHashSet<String> target, List<String> values) {
        for (String value : values) {
            String sanitized = sanitize(value);
            if (StringUtils.hasText(sanitized)) {
                target.add(sanitized);
            }
            if (target.size() >= MAX_CONTEXT_ITEMS) {
                break;
            }
        }
    }

    private List<String> boundedList(LinkedHashSet<String> values) {
        return values.stream().limit(MAX_CONTEXT_ITEMS).toList();
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]", " ")
                .replace('、', ' ')
                .trim();
        if (normalized.length() > MAX_CONTEXT_ITEM_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_ITEM_LENGTH);
        }
        return normalized;
    }

    public record FeedbackContext(
            List<String> liked,
            List<String> disliked,
            List<String> cooked
    ) {
        public FeedbackContext {
            liked = liked == null ? List.of() : List.copyOf(liked);
            disliked = disliked == null ? List.of() : List.copyOf(disliked);
            cooked = cooked == null ? List.of() : List.copyOf(cooked);
        }

        public static FeedbackContext empty() {
            return new FeedbackContext(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return liked.isEmpty() && disliked.isEmpty() && cooked.isEmpty();
        }

        public String promptSection() {
            if (isEmpty()) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            lines.add("用户近期推荐反馈（仅作为推荐参考，不改变本次明确输入、忌口和过敏约束）：");
            if (!liked.isEmpty()) {
                lines.add("用户喜欢的菜名或食材（可优先考虑）：" + String.join("、", liked));
            }
            if (!disliked.isEmpty()) {
                lines.add("用户不喜欢的菜名或食材（尽量避免重复，但本次明确输入优先）：" + String.join("、", disliked));
            }
            if (!cooked.isEmpty()) {
                lines.add("用户近期已做过的菜名或食材（优先提供不同组合）：" + String.join("、", cooked));
            }
            String value = String.join("\\n", lines);
            return value.length() <= MAX_CONTEXT_LENGTH ? value : value.substring(0, MAX_CONTEXT_LENGTH);
        }
    }
}
