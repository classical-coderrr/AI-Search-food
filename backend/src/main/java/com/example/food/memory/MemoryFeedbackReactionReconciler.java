package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** Reconciles legacy recipe feedback candidates against the latest user action. */
@Service
public class MemoryFeedbackReactionReconciler {

    private static final int MAX_SOURCES_PER_PASS = 500;

    private final MemoryCandidateMapper candidateMapper;
    private final MemoryEpisodeService episodeService;
    private final ObjectMapper objectMapper;

    public MemoryFeedbackReactionReconciler(
            MemoryCandidateMapper candidateMapper,
            MemoryEpisodeService episodeService,
            ObjectMapper objectMapper
    ) {
        this.candidateMapper = candidateMapper;
        this.episodeService = episodeService;
        this.objectMapper = objectMapper;
    }

    public int reconcile(Long userId) {
        if (userId == null || userId <= 0) return 0;
        int superseded = 0;
        for (String sourceId : candidateMapper.listOwnedRecipeFeedbackSourcesForReconciliation(
                userId, MAX_SOURCES_PER_PASS)) {
            if (!StringUtils.hasText(sourceId)) continue;
            MemoryEpisode latestReaction = episodeService.listOwnedRecipeFeedbackBySource(userId, sourceId).stream()
                    .filter(episode -> reactionAction(episode) != null)
                    .findFirst()
                    .orElse(null);
            if (latestReaction == null) continue;

            String latestAction = reactionAction(latestReaction);
            for (MemoryCandidate candidate : candidateMapper
                    .listOwnedRecipeFeedbackCandidatesBySource(userId, sourceId)) {
                if ("CONFIRM".equalsIgnoreCase(candidate.getUserDecision())) continue;
                boolean currentReaction = "REACTION".equals(latestAction)
                        && latestReaction.getId().equals(candidate.getEpisodeId());
                if (!currentReaction && candidateMapper.markSuperseded(
                        userId, candidate.getId(), candidate.getVersion()) == 1) {
                    superseded++;
                }
            }
        }
        return superseded;
    }

    private String reactionAction(MemoryEpisode episode) {
        if (episode == null || !"RECIPE_FEEDBACK".equalsIgnoreCase(episode.getEpisodeType())
                || !StringUtils.hasText(episode.getPayloadJson())) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(episode.getPayloadJson());
            String action = payload == null ? null : payload.path("action").asText(null);
            if (!StringUtils.hasText(action)) return null;
            String normalized = action.trim().toUpperCase(Locale.ROOT);
            return "REACTION".equals(normalized) || "REACTION_CLEARED".equals(normalized)
                    ? normalized : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
