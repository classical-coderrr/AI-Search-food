package com.example.food.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies deterministic guardrails to memory claims in final Agent answers. */
public final class AgentMemoryAnswerGuard {

    private static final Pattern ABSOLUTE_HISTORY_CLAIM = Pattern.compile(
            "(?:你|用户)[^。！？!?\\n]{0,160}(?:从未|从来没有|从不|从没)[^。！？!?\\n]{0,220}[。！？!?]?"
    );
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?\\n])");
    private static final Pattern TIMELINE_CONTRADICTION = Pattern.compile("时间线(?:存在|上)?(?:明确的)?矛盾");
    private static final Pattern EVENT_TIMESTAMP = Pattern.compile(
            "(?<!\\d)(?:\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2})?"
                    + "|\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)"
    );
    private static final Pattern SHORT_DATE_TIMESTAMP = Pattern.compile(
            "(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)"
    );
    private static final Pattern DATED_EPISODE_SUMMARY = Pattern.compile(
            "^历史行为（发生于 \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}）："
    );
    private static final List<String> NEGATIVE_PREFERENCE_TERMS = List.of(
            "不喜欢", "不爱", "不吃", "忌口", "避免", "避开", "不想吃", "省略", "不添加", "不放"
    );
    private static final List<String> USER_ATTRIBUTION_TERMS = List.of(
            "根据你", "基于你", "按你的", "根据你的", "你反馈", "你的反馈", "你之前", "你最近",
            "你近期", "你明确说", "你偏好", "你不喜欢", "你不吃", "你曾说", "用户反馈"
    );
    private static final List<String> NEGATED_CLAIM_TERMS = List.of(
            "并非", "不是因为", "不代表", "不能推断", "不得", "不应", "没有依据", "不意味着"
    );
    private static final List<String> ALREADY_QUALIFIED_TERMS = List.of(
            "当前记忆", "本轮记忆", "当前检索", "无法核实", "不足以核实", "不能据此断言"
    );
    private static final List<String> POSITIVE_TRACE_PREFIXES = List.of(
            "长期偏好：喜欢", "近期行为推断：喜欢", "相关记忆：喜欢",
            "食材偏好：喜欢", "食材偏好（近期行为推断）：喜欢", "食材偏好（长期）：喜欢",
            "菜谱偏好：喜欢", "菜谱偏好（近期行为推断）：喜欢", "菜谱偏好（长期）：喜欢",
            "行为习惯：喜欢", "行为习惯（近期行为推断）：喜欢", "行为习惯（长期）：喜欢"
    );
    private static final List<String> NEGATIVE_EVIDENCE_TERMS = List.of("不喜欢", "不吃", "忌口", "DISLIKE");
    private static final List<String> EVENT_FACT_TERMS = List.of(
            "历史行为", "记忆事件", "库存", "入库", "消耗", "撤销", "操作记录", "收藏记录",
            "烹饪记录", "评价记录", "Episode", "episode"
    );

    private AgentMemoryAnswerGuard() { }

    public static String guard(String answer, List<String> contextSections, List<String> traceSummaries) {
        if (answer == null || answer.isBlank() || contextSections == null
                || contextSections.stream().noneMatch(section ->
                "PERSONAL_MEMORY".equals(section) || "STRUCTURED_PROFILE".equals(section))) {
            return answer;
        }

        String guarded = softenAbsoluteHistoryClaims(answer);
        guarded = clarifyTimelineConflict(guarded, traceSummaries);
        guarded = guardUnsupportedEventTimestamps(guarded, traceSummaries);
        List<String> positiveEntities = positiveEntities(traceSummaries);
        if (positiveEntities.isEmpty()) return guarded;

        List<String> corrected = new ArrayList<>();
        for (String sentence : SENTENCE_BOUNDARY.split(guarded, -1)) {
            corrected.add(correctContradictoryClaim(sentence, positiveEntities, traceSummaries));
        }
        return String.join("", corrected);
    }

    private static String guardUnsupportedEventTimestamps(String answer, List<String> traceSummaries) {
        List<String> verifiedEpisodeTimestamps = traceSummaries == null ? List.of() : traceSummaries.stream()
                .filter(summary -> summary != null && summary.startsWith("历史行为（发生于 "))
                .flatMap(summary -> {
                    Matcher matcher = EVENT_TIMESTAMP.matcher(summary);
                    List<String> timestamps = new ArrayList<>();
                    while (matcher.find()) timestamps.add(normalizeTimestamp(matcher.group()));
                    return timestamps.stream();
                })
                .toList();
        List<String> guarded = new ArrayList<>();
        for (String sentence : SENTENCE_BOUNDARY.split(answer, -1)) {
            Matcher matcher = EVENT_TIMESTAMP.matcher(sentence);
            boolean containsUnsupportedTime = false;
            while (matcher.find()) {
                String claimed = normalizeTimestamp(matcher.group());
                if (containsAny(sentence, EVENT_FACT_TERMS)
                        && verifiedEpisodeTimestamps.stream().noneMatch(value -> timestampMatches(value, claimed))) {
                    containsUnsupportedTime = true;
                    break;
                }
            }
            guarded.add(containsUnsupportedTime
                    ? "本轮检索到的记忆中没有这条事件时间依据，因此无法确认该记录。"
                    : sentence);
        }
        return String.join("", guarded);
    }

    private static boolean timestampMatches(String verified, String claimed) {
        if (claimed.startsWith("--")) {
            String monthAndDay = claimed.substring(2);
            return verified.length() >= 16
                    && timestampPrefixMatches(verified.substring(5), monthAndDay);
        }
        return timestampPrefixMatches(verified, claimed);
    }

    private static boolean timestampPrefixMatches(String verified, String claimed) {
        return verified.equals(claimed) || verified.startsWith(claimed + ":")
                || verified.startsWith(claimed + " ");
    }

    private static String normalizeTimestamp(String value) {
        String normalized = value.trim().replace('T', ' ').replaceAll("\\s+", " ");
        Matcher shortDate = SHORT_DATE_TIMESTAMP.matcher(normalized);
        if (shortDate.matches()) {
            return "--" + String.format(java.util.Locale.ROOT, "%02d-%02d %s",
                    Integer.parseInt(shortDate.group(1)), Integer.parseInt(shortDate.group(2)), shortDate.group(3));
        }
        return normalized;
    }

    private static String clarifyTimelineConflict(String answer, List<String> traceSummaries) {
        if (traceSummaries == null || traceSummaries.stream()
                .filter(summary -> summary != null && DATED_EPISODE_SUMMARY.matcher(summary).find())
                .limit(2).count() < 2) {
            return answer;
        }
        Matcher matcher = TIMELINE_CONTRADICTION.matcher(answer);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int sentenceStart = Math.max(Math.max(answer.lastIndexOf('。', matcher.start()),
                            answer.lastIndexOf('！', matcher.start())),
                    Math.max(answer.lastIndexOf('？', matcher.start()), answer.lastIndexOf('\n', matcher.start()))) + 1;
            String prefix = answer.substring(sentenceStart, matcher.start());
            if (containsAny(prefix, List.of("并非", "不是", "不构成", "不存在", "并不", "没有"))) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result,
                        Matcher.quoteReplacement("正反反馈方向冲突（事件先后以记录时间为准）"));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String softenAbsoluteHistoryClaims(String answer) {
        Matcher matcher = ABSOLUTE_HISTORY_CLAIM.matcher(answer);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String claim = matcher.group();
            if (containsAny(claim, ALREADY_QUALIFIED_TERMS)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(claim));
            } else {
                matcher.appendReplacement(result,
                        Matcher.quoteReplacement("本轮检索到的记忆不足以核实完整历史，不能据此断言你从未表达过。"));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String correctContradictoryClaim(String sentence, List<String> positiveEntities,
                                                    List<String> traceSummaries) {
        if (containsAny(sentence, NEGATED_CLAIM_TERMS)
                || !containsAny(sentence, NEGATIVE_PREFERENCE_TERMS)
                || !containsAny(sentence, USER_ATTRIBUTION_TERMS)) {
            return sentence;
        }
        for (String entity : positiveEntities) {
            if (sentence.contains(entity)) {
                if (hasNegativeEvidence(entity, traceSummaries)) return sentence;
                String evidence = traceSummaries.stream()
                        .filter(summary -> summary != null && summary.endsWith(entity))
                        .filter(summary -> summary.contains("喜欢"))
                        .findFirst()
                        .orElse("当前记忆显示可能喜欢" + entity);
                return "本轮记忆记录为“" + evidence + "”，不能据此声称你反馈要求避开该食材。";
            }
        }
        return sentence;
    }

    private static List<String> positiveEntities(List<String> traceSummaries) {
        if (traceSummaries == null) return List.of();
        List<String> entities = new ArrayList<>();
        for (String summary : traceSummaries) {
            if (summary == null) continue;
            for (String prefix : POSITIVE_TRACE_PREFIXES) {
                if (summary.startsWith(prefix)) {
                    String entity = summary.substring(prefix.length()).trim();
                    if (!entity.isEmpty() && !entities.contains(entity)) entities.add(entity);
                    break;
                }
            }
        }
        return entities;
    }

    private static boolean hasNegativeEvidence(String entity, List<String> traceSummaries) {
        return traceSummaries != null && traceSummaries.stream()
                .filter(summary -> summary != null && summary.contains(entity))
                .anyMatch(summary -> containsAny(summary, NEGATIVE_EVIDENCE_TERMS));
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }
}
