package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Retracts ingredient-pattern evidence that came from a recipe the user later unsaved. */
@Service
public class MemoryUnsavedBehaviorReconciler {

    private static final int MAX_CANDIDATES_PER_EPISODE = 100;

    private final MemoryEpisodeService episodeService;
    private final MemoryCandidateMapper candidateMapper;
    private final ObjectMapper objectMapper;

    public MemoryUnsavedBehaviorReconciler(
            MemoryEpisodeService episodeService,
            MemoryCandidateMapper candidateMapper,
            ObjectMapper objectMapper
    ) {
        this.episodeService = episodeService;
        this.candidateMapper = candidateMapper;
        this.objectMapper = objectMapper;
    }

    public int reconcile(Long userId, MemoryEpisode episode) {
        if (userId == null || userId <= 0 || episode == null
                || !"RECIPE_UNSAVED".equalsIgnoreCase(episode.getEpisodeType())
                || episode.getOccurredAt() == null) {
            return 0;
        }
        String recipeId = recipeId(episode);
        if (!StringUtils.hasText(recipeId)) return 0;

        int superseded = 0;
        for (MemoryEpisode savedEpisode : episodeService.listOwnedRecipeSavesBySource(
                userId, recipeId, episode.getOccurredAt(), episode.getId())) {
            for (MemoryCandidate candidate : candidateMapper.listOwned(
                    userId, savedEpisode.getId(), "INGREDIENT_PREFERENCE", MAX_CANDIDATES_PER_EPISODE)) {
                if (!"IMPLICIT_BEHAVIOR".equalsIgnoreCase(candidate.getSourceType())
                        || !"RECENT".equalsIgnoreCase(candidate.getTemporalType())) {
                    continue;
                }
                if (candidateMapper.markSuperseded(userId, candidate.getId(), candidate.getVersion()) == 1) {
                    superseded++;
                }
            }
        }
        return superseded;
    }

    private String recipeId(MemoryEpisode episode) {
        if (!StringUtils.hasText(episode.getPayloadJson())) return episode.getSourceId();
        try {
            JsonNode payload = objectMapper.readTree(episode.getPayloadJson());
            String id = payload == null ? null : payload.path("recipeId").asText(null);
            return StringUtils.hasText(id) ? id.trim() : episode.getSourceId();
        } catch (Exception ignored) {
            return episode.getSourceId();
        }
    }
}
