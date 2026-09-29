package com.example.food.memory;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
public class MemoryEmbeddingIndexService {
    private final MemoryEmbeddingMapper mapper;
    private final MemoryEmbeddingClient embeddingClient;
    private final MemoryVectorStoreAdapter vectorStore;
    private final MemoryEmbeddingProperties properties;
    private final MemoryPersonalizationService personalizationService;
    private final MemoryEmbeddingIndexJobService jobService;

    public MemoryEmbeddingIndexService(MemoryEmbeddingMapper mapper, MemoryEmbeddingClient embeddingClient,
                                       MemoryVectorStoreAdapter vectorStore, MemoryEmbeddingProperties properties,
                                       MemoryPersonalizationService personalizationService,
                                       MemoryEmbeddingIndexJobService jobService) {
        this.mapper = mapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.personalizationService = personalizationService;
        this.jobService = jobService;
    }

    public int indexPending() {
        if (!embeddingClient.isAvailable()) return 0;
        List<MemoryEmbeddingCandidate> candidates = mapper.findUnindexedCandidates(embeddingClient.model(),
                properties.dimensions(),
                Math.max(1, Math.min(properties.pollBatchSize(), 200)), properties.indexUserId(),
                LocalDateTime.now());
        Map<Long, List<MemoryEmbeddingCandidate>> byUser = new LinkedHashMap<>();
        for (MemoryEmbeddingCandidate candidate : candidates) {
            if (candidate.getUserId() != null) {
                byUser.computeIfAbsent(candidate.getUserId(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        int indexed = 0;
        for (List<MemoryEmbeddingCandidate> userCandidates : byUser.values()) {
            Long userId = userCandidates.get(0).getUserId();
            if (!personalizationService.isEnabled(userId)) continue;
            for (int start = 0; start < userCandidates.size(); start += batchSize()) {
                List<MemoryEmbeddingCandidate> candidatesBatch = userCandidates.subList(start,
                        Math.min(userCandidates.size(), start + batchSize()));
                List<ClaimedCandidate> batch = new ArrayList<>(candidatesBatch.size());
                for (MemoryEmbeddingCandidate candidate : candidatesBatch) {
                    MemoryEmbeddingIndexJob job = jobService.claim(candidate, embeddingClient.model(),
                            properties.dimensions());
                    if (job != null) batch.add(new ClaimedCandidate(candidate, job));
                }
                indexed += indexBatch(batch);
            }
        }
        return indexed;
    }

    public List<MemoryVectorMatch> search(Long userId, String query, int limit) {
        if (!embeddingClient.isAvailable() || !StringUtils.hasText(query)
                || userId == null || userId <= 0 || !personalizationService.isEnabled(userId)) return List.of();
        float[] vector = embeddingClient.embedOne(query);
        if (vector == null) return List.of();
        int boundedLimit = Math.max(1, Math.min(limit, properties.scanLimit()));
        return vectorStore.search(userId, embeddingClient.model(), vector, boundedLimit);
    }

    public void deleteSource(Long userId, String sourceKind, Long sourceId) {
        vectorStore.deleteSource(userId, sourceKind, sourceId);
    }

    public void deleteAll(Long userId) {
        vectorStore.deleteAll(userId);
    }

    private int indexBatch(List<ClaimedCandidate> batch) {
        if (batch.isEmpty()) return 0;
        List<String> content = batch.stream().map(ClaimedCandidate::candidate)
                .map(MemoryEmbeddingCandidate::getContent)
                .map(this::boundedContent).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(content);
        } catch (RuntimeException failure) {
            retryAll(batch, failure.getClass().getSimpleName());
            return 0;
        }
        if (vectors == null || vectors.size() != batch.size()) {
            retryAll(batch, "EMBEDDING_RESPONSE_EMPTY_OR_MISMATCHED");
            return 0;
        }
        int indexed = 0;
        for (int i = 0; i < batch.size(); i++) {
            ClaimedCandidate claimed = batch.get(i);
            MemoryEmbeddingCandidate candidate = claimed.candidate();
            float[] vector = vectors.get(i);
            if (!validVector(vector)) {
                jobService.retry(claimed.job(), "EMBEDDING_DIMENSION_OR_VALUE_INVALID");
                continue;
            }
            try {
                vectorStore.upsert(new MemoryVectorDocument(candidate.getUserId(), candidate.getSourceKind(),
                        candidate.getSourceId(), candidate.getMemoryType(), candidate.getSourceVersion(),
                        embeddingClient.model(), vector));
                if (jobService.complete(claimed.job())) indexed++;
            } catch (RuntimeException failure) {
                jobService.retry(claimed.job(), failure.getClass().getSimpleName());
            }
        }
        return indexed;
    }

    private void retryAll(List<ClaimedCandidate> batch, String failureCode) {
        for (ClaimedCandidate candidate : batch) jobService.retry(candidate.job(), failureCode);
    }

    private boolean validVector(float[] vector) {
        if (vector == null || vector.length != properties.dimensions()) return false;
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private int batchSize() {
        return Math.max(1, Math.min(properties.batchSize(), 10));
    }

    private String boundedContent(String text) {
        if (!StringUtils.hasText(text)) return "";
        String normalized = text.trim();
        return normalized.length() > 8000 ? normalized.substring(0, 8000) : normalized;
    }

    private record ClaimedCandidate(MemoryEmbeddingCandidate candidate, MemoryEmbeddingIndexJob job) { }
}
