package com.example.food.memory;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic token-budget allocator; truncation happens at code-point boundaries. */
@Service
public class ContextBudgetManager {

    public BudgetResult allocate(List<ContextEntry> entries, int requestedBudget) {
        int budget = Math.max(1, Math.min(requestedBudget, 20_000));
        List<ContextEntry> ordered = entries.stream()
                .sorted(java.util.Comparator.comparingInt(ContextEntry::priority).reversed()
                        .thenComparingInt(ContextEntry::order))
                .toList();
        List<IncludedEntry> included = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int used = 0;
        boolean truncated = false;
        for (ContextEntry entry : ordered) {
            if (!StringUtils.hasText(entry.text())) continue;
            String fingerprint = normalizeForDedup(entry.section() + "|" + entry.text());
            if (!seen.add(fingerprint)) continue;
            int remaining = budget - used;
            if (remaining <= 0) {
                truncated = true;
                continue;
            }
            String text = entry.text().trim();
            int estimate = estimateTokens(text);
            int entryBudget = Math.max(1, (int) Math.ceil(budget * sectionShare(entry.section())));
            int allocation = Math.min(remaining, entryBudget);
            boolean wasTruncated = estimate > allocation;
            if (wasTruncated) {
                text = prefixWithinBudget(text, Math.max(0, allocation - 1)) + "…";
                estimate = estimateTokens(text);
                truncated = true;
            }
            included.add(new IncludedEntry(entry.section(), entry.sourceKind(), entry.id(), text, estimate));
            used += estimate;
        }
        return new BudgetResult(List.copyOf(included), used, budget, truncated);
    }

    public int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) return 0;
        int tokens = 0;
        int latinRun = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                tokens += (latinRun + 3) / 4;
                latinRun = 0;
            } else if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                tokens += (latinRun + 3) / 4 + 1;
                latinRun = 0;
            } else if (Character.isLetterOrDigit(codePoint)) {
                latinRun++;
            } else {
                tokens += (latinRun + 3) / 4 + 1;
                latinRun = 0;
            }
        }
        tokens += (latinRun + 3) / 4;
        return tokens;
    }

    private String prefixWithinBudget(String text, int maxTokens) {
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = text.offsetByCodePoints(0, middle);
            if (estimateTokens(text.substring(0, end)) <= maxTokens) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)).stripTrailing();
    }

    private String normalizeForDedup(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private double sectionShare(String section) {
        return switch (section) {
            case "SESSION" -> 0.30;
            case "TOOL_RESULTS" -> 0.25;
            case "PERSONAL_MEMORY" -> 0.35;
            case "MEMORY_CONFLICTS" -> 0.20;
            case "PERSONALIZED_SKILL" -> 0.15;
            case "STRUCTURED_PROFILE" -> 0.20;
            case "KNOWLEDGE_RAG" -> 0.30;
            default -> 0.20;
        };
    }

    public record ContextEntry(String section, String sourceKind, Long id, String text,
                               int priority, int order) { }
    public record IncludedEntry(String section, String sourceKind, Long id, String text,
                                int estimatedTokens) { }
    public record BudgetResult(List<IncludedEntry> entries, int estimatedTokens,
                               int tokenBudget, boolean truncated) { }
}
