package com.example.food.memory;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Allowlisted, explicit execution preferences that can safely become a personalized skill. */
public final class SkillPreferenceCatalog {
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "CANDIDATE_COUNT", Set.of("1", "2", "3", "4", "5"),
            "MAX_COOKING_TIME", Set.of("15", "20", "30", "45", "60"),
            "RESPONSE_STYLE", Set.of("CONCISE", "DETAILED"),
            "INCLUDE_INGREDIENT_WEIGHT", Set.of("YES", "NO")
    );

    private SkillPreferenceCatalog() { }

    public static List<String> keys() {
        return List.of("CANDIDATE_COUNT", "MAX_COOKING_TIME", "RESPONSE_STYLE", "INCLUDE_INGREDIENT_WEIGHT");
    }

    public static String normalizeKey(String value) {
        if (!StringUtils.hasText(value)) return null;
        String key = value.trim().toUpperCase(Locale.ROOT);
        return OPTIONS.containsKey(key) ? key : null;
    }

    public static String normalizeValue(String key, String value) {
        String normalizedKey = normalizeKey(key);
        if (normalizedKey == null || !StringUtils.hasText(value)) return null;
        String normalizedValue = value.trim().toUpperCase(Locale.ROOT);
        return OPTIONS.get(normalizedKey).contains(normalizedValue) ? normalizedValue : null;
    }

    public static Set<String> allowedValues(String key) {
        String normalizedKey = normalizeKey(key);
        return normalizedKey == null ? Set.of() : OPTIONS.get(normalizedKey);
    }

    public static boolean evidenceSupports(String key, String value, String evidence) {
        String normalizedKey = normalizeKey(key);
        String normalizedValue = normalizeValue(key, value);
        if (normalizedKey == null || normalizedValue == null || !StringUtils.hasText(evidence)) return false;
        return switch (normalizedKey) {
            case "CANDIDATE_COUNT" -> supportsCandidateCount(normalizedValue, evidence);
            case "MAX_COOKING_TIME" -> supportsCookingTime(normalizedValue, evidence);
            case "RESPONSE_STYLE" -> supportsResponseStyle(normalizedValue, evidence);
            case "INCLUDE_INGREDIENT_WEIGHT" -> supportsIngredientWeight(normalizedValue, evidence);
            default -> false;
        };
    }

    private static boolean supportsCandidateCount(String value, String evidence) {
        List<String> forms = switch (value) {
                case "1" -> List.of("1个", "1道", "一个", "一道", "一份");
                case "2" -> List.of("2个", "2道", "两个", "两道", "两份");
                case "3" -> List.of("3个", "3道", "三个", "三道", "三份");
                case "4" -> List.of("4个", "4道", "四个", "四道", "四份");
                case "5" -> List.of("5个", "5道", "五个", "五道", "五份");
                default -> List.of();
        };
        if (!containsAny(evidence, forms)) return false;
        List<String> negatedForms = forms.stream().flatMap(form -> List.of(
                "不要" + form, "不要给我" + form, "不需要" + form, "不需要给我" + form,
                "不想要" + form, "别给我" + form, "别要" + form).stream()).toList();
        return !containsAny(evidence, negatedForms);
    }

    private static boolean supportsCookingTime(String value, String evidence) {
        List<String> forms = switch (value) {
            case "15" -> List.of("15分钟以内", "15分钟内", "最多15分钟", "不超过15分钟", "不要超过15分钟",
                    "别超过15分钟", "一刻钟以内", "一刻钟内");
            case "20" -> List.of("20分钟以内", "20分钟内", "最多20分钟", "不超过20分钟", "不要超过20分钟",
                    "别超过20分钟", "二十分钟以内", "二十分钟内");
            case "30" -> List.of("30分钟以内", "30分钟内", "最多30分钟", "不超过30分钟",
                    "不要超过30分钟", "别超过30分钟", "三十分钟以内", "三十分钟内", "半小时以内", "半小时内",
                    "最多半小时", "不超过半小时", "不要超过半小时", "别超过半小时",
                    "半个小时以内", "半个小时内");
            case "45" -> List.of("45分钟以内", "45分钟内", "最多45分钟", "不超过45分钟", "不要超过45分钟",
                    "别超过45分钟", "四十五分钟以内", "四十五分钟内");
            case "60" -> List.of("60分钟以内", "60分钟内", "最多60分钟", "不超过60分钟",
                    "不要超过60分钟", "别超过60分钟", "一小时以内", "一小时内", "最多一小时", "不超过一小时",
                    "不要超过一小时", "别超过一小时", "一个小时以内", "一个小时内", "1小时以内", "1小时内");
            default -> List.of();
        };
        if (!containsAny(evidence, forms)) return false;
        List<String> negatedForms = forms.stream().flatMap(form -> List.of(
                "不要" + form, "不想要" + form, "不需要" + form, "不喜欢" + form,
                "别推荐" + form, "别选" + form).stream()).toList();
        return !containsAny(evidence, negatedForms);
    }

    private static boolean supportsResponseStyle(String value, String evidence) {
        List<String> negativeDetail = List.of("不要太详细", "不需要太详细", "别太详细", "不要详细说明",
                "不需要详细说明", "不用展开说明", "别展开说明", "不要详细步骤", "不需要详细步骤",
                "别给我详细步骤", "不要讲详细", "不需要讲详细", "不要展开细节", "别展开细节");
        if ("CONCISE".equals(value)) {
            return containsAny(evidence, List.of("简洁", "简短", "短一点", "别太长", "别啰嗦", "精简"))
                    || containsAny(evidence, negativeDetail);
        }
        return containsAny(evidence, List.of("详细一点", "更详细", "讲详细", "详细说明", "详细步骤",
                "具体一些", "讲清楚", "展开说明")) && !containsAny(evidence, negativeDetail);
    }

    private static boolean supportsIngredientWeight(String value, String evidence) {
        List<String> negative = List.of("不用标克数", "不需要克数", "不用写克数", "不用写食材克数",
                "不需要写食材克数", "不需要显示重量", "不用写重量", "不要具体用量", "不需要具体用量",
                "不用具体用量", "不要食材用量", "不需要食材用量", "不用食材用量",
                "不需要写克数", "无需克数", "别标克数", "不要克数");
        if ("YES".equals(value)) {
            return containsAny(evidence, List.of("标注食材克数", "标出食材克数", "标出食材的克数",
                    "请标出食材克数", "请给我标出食材的克数", "标出克数", "写出克数", "注明克数",
                    "给出克数", "需要克数", "食材用量", "标注重量", "具体用量"))
                    && !containsAny(evidence, negative);
        }
        return containsAny(evidence, negative);
    }

    public static String displayName(String key) {
        return switch (normalizeKey(key) == null ? "" : normalizeKey(key)) {
            case "CANDIDATE_COUNT" -> "每次推荐数量";
            case "MAX_COOKING_TIME" -> "烹饪时长上限（分钟）";
            case "RESPONSE_STYLE" -> "回答风格";
            case "INCLUDE_INGREDIENT_WEIGHT" -> "标注食材克数";
            default -> "执行习惯";
        };
    }

    public static String strategyKey(String key) {
        return switch (normalizeKey(key) == null ? "" : normalizeKey(key)) {
            case "CANDIDATE_COUNT" -> "candidateCount";
            case "MAX_COOKING_TIME" -> "maxCookingTime";
            case "RESPONSE_STYLE" -> "responseStyle";
            case "INCLUDE_INGREDIENT_WEIGHT" -> "includeIngredientWeight";
            default -> null;
        };
    }

    public static String displayValue(String key, String value) {
        String normalizedKey = normalizeKey(key);
        String normalizedValue = normalizeValue(key, value);
        if (normalizedKey == null || normalizedValue == null) return value == null ? "" : value;
        return switch (normalizedKey) {
            case "RESPONSE_STYLE" -> "CONCISE".equals(normalizedValue) ? "简洁" : "详细";
            case "INCLUDE_INGREDIENT_WEIGHT" -> "YES".equals(normalizedValue) ? "是" : "否";
            default -> normalizedValue;
        };
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }
}
