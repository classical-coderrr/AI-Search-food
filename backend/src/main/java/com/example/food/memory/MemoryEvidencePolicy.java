package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Keeps raw provenance intact while counting only independent daily evidence. */
@Component
public class MemoryEvidencePolicy {
    private final MemoryEpisodeMapper episodes;
    private final ObjectMapper mapper;

    public MemoryEvidencePolicy(MemoryEpisodeMapper episodes, ObjectMapper mapper) {
        this.episodes = episodes;
        this.mapper = mapper;
    }

    public List<MemoryCandidate> effective(Long userId, List<MemoryCandidate> candidates) {
        List<Long> ids = candidates.stream().map(MemoryCandidate::getEpisodeId).distinct().toList();
        if (ids.isEmpty()) return List.of();
        Map<Long, MemoryEpisode> source = episodes.findOwnedByIds(userId, ids).stream()
                .collect(Collectors.toMap(MemoryEpisode::getId, Function.identity()));
        Map<String, MemoryCandidate> buckets = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            MemoryEpisode episode = source.get(candidate.getEpisodeId());
            if (episode == null) continue;
            String dish = candidate.getCanonicalEntity();
            try {
                JsonNode payload = mapper.readTree(episode.getPayloadJson());
                dish = payload.path("recipeTitle").asText(payload.path("title").asText(dish));
                if ("USER_CHAT".equals(episode.getEpisodeType())) {
                    for (JsonNode fact : payload.path("chatFacts")) {
                        if ("FOOD_REQUEST".equals(fact.path("kind").asText())) {
                            dish = fact.path("entity").asText(dish);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Legacy evidence can still be grouped by its canonical entity.
            }
            String bucket = episode.getOccurredAt().toLocalDate() + "|" + normalize(dish);
            buckets.merge(bucket, candidate, (first, second) ->
                    second.getConfidence().compareTo(first.getConfidence()) > 0 ? second : first);
        }
        return new ArrayList<>(buckets.values());
    }

    private String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
    }
}
