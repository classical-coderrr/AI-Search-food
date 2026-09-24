package com.example.food.memory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic first version of memory-specific query understanding. */
@Service
public class MemoryQueryPlanner {

    private final Clock clock;

    public MemoryQueryPlanner() {
        this(Clock.systemDefaultZone());
    }

    MemoryQueryPlanner(Clock clock) {
        this.clock = clock;
    }

    public MemoryQueryPlan plan(Long userId, MemorySearchCommand command) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
        if (command == null || !StringUtils.hasText(command.query())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆检索问题不能为空");
        }
        if (command.minConfidence() != null && !validUnit(command.minConfidence())
                || command.minImportance() != null && !validUnit(command.minImportance())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "置信度和重要性阈值必须在 0 到 1 之间");
        }
        if (command.timeFrom() != null && command.timeTo() != null
                && command.timeFrom().isAfter(command.timeTo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆检索时间范围无效");
        }

        String query = command.query().trim();
        if (query.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆检索问题过长");
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        Set<String> scenes = normalizedList(command.scenes());
        Set<String> meals = normalizedList(command.mealTypes());
        Set<String> ingredients = normalizedList(command.ingredients());
        Set<String> dietGoals = normalizedList(command.dietGoals());
        String intent = "GENERAL_MEMORY_RECALL";
        List<String> rewriteTerms = new ArrayList<>();

        if (containsAny(normalized, "健身", "训练", "运动后", "post workout", "workout")) {
            intent = "POST_WORKOUT_RECIPE_RECALL";
            rewriteTerms.add("训练后 健身后 高蛋白 饮食历史");
        }
        if (containsAny(normalized, "晚餐", "晚饭", "今晚", "dinner", "tonight")) {
            if ("GENERAL_MEMORY_RECALL".equals(intent)) {
                intent = "DINNER_MEMORY_RECALL";
            }
            rewriteTerms.add("晚餐 晚饭");
        }
        if (containsAny(normalized, "菜谱", "食谱", "做过", "收藏", "评分", "recipe")) {
            rewriteTerms.add("菜谱 历史选择 用户反馈");
        }
        LocalDateTime from = command.timeFrom();
        LocalDateTime to = command.timeTo();
        LocalDate today = LocalDate.now(clock);
        if (containsAny(normalized, "昨天", "yesterday")) {
            from = today.minusDays(1).atStartOfDay();
            to = today.atStartOfDay().minusNanos(1);
            rewriteTerms.add("昨天");
        } else if (containsAny(normalized, "最近一周", "近一周", "这一周", "last week")) {
            from = today.minusDays(7).atStartOfDay();
            to = LocalDateTime.now(clock);
        }

        String rewritten = query + (rewriteTerms.isEmpty() ? "" : " " + String.join(" ", rewriteTerms));
        return new MemoryQueryPlan(
                query,
                intent,
                rewritten,
                command.sessionId(),
                normalizedList(command.memoryTypes()).stream().toList(),
                normalizedList(command.episodeTypes()).stream().toList(),
                List.copyOf(scenes),
                List.copyOf(meals),
                from,
                to,
                List.copyOf(ingredients),
                List.copyOf(dietGoals),
                command.minConfidence(),
                command.minImportance(),
                Math.max(1, Math.min(command.limit() == null || command.limit() < 1 ? 8 : command.limit(), 50))
        );
    }

    private Set<String> normalizedList(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(StringUtils::hasText)
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .filter(value -> value.length() <= 128)
                    .limit(20).forEach(result::add);
        }
        return result;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private boolean validUnit(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }
}
