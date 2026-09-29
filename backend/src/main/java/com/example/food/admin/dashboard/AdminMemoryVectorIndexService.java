package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexRebuildResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexStatusResponse;
import com.example.food.memory.MemoryEmbeddingMapper;
import com.example.food.memory.MemoryEmbeddingProperties;
import com.example.food.memory.MemoryVectorIndexJobMapper;
import com.example.food.memory.MemoryVectorIndexJobService;
import com.example.food.memory.QdrantMemoryVectorIndex;
import com.example.food.memory.QdrantMemoryVectorProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AdminMemoryVectorIndexService {
    private final MemoryEmbeddingMapper embeddingMapper;
    private final MemoryVectorIndexJobMapper jobMapper;
    private final MemoryVectorIndexJobService jobService;
    private final QdrantMemoryVectorIndex vectorIndex;
    private final QdrantMemoryVectorProperties qdrantProperties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final Clock clock;

    public AdminMemoryVectorIndexService(MemoryEmbeddingMapper embeddingMapper,
                                         MemoryVectorIndexJobMapper jobMapper,
                                         MemoryVectorIndexJobService jobService,
                                         QdrantMemoryVectorIndex vectorIndex,
                                         QdrantMemoryVectorProperties qdrantProperties,
                                         MemoryEmbeddingProperties embeddingProperties,
                                         Clock clock) {
        this.embeddingMapper = embeddingMapper;
        this.jobMapper = jobMapper;
        this.jobService = jobService;
        this.vectorIndex = vectorIndex;
        this.qdrantProperties = qdrantProperties;
        this.embeddingProperties = embeddingProperties;
        this.clock = clock;
    }

    public AdminMemoryVectorIndexStatusResponse status() {
        String model = embeddingProperties.model();
        int databaseVectors = embeddingMapper.countVectorsByModel(model);
        int indexedVectors = embeddingMapper.countIndexedVectorsByModel(model);
        Map<String, Long> jobs = jobCounts(jobMapper.countUpsertJobsByStatus(model));
        QdrantMemoryVectorIndex.CollectionStatus qdrant = vectorIndex.inspectCollection();
        return new AdminMemoryVectorIndexStatusResponse(Instant.now(clock), qdrantProperties.enabled(), model,
                qdrant.state(), qdrant.available(), databaseVectors, indexedVectors,
                Math.max(0, databaseVectors - indexedVectors), jobs.getOrDefault("PENDING", 0L),
                jobs.getOrDefault("PROCESSING", 0L), jobs.getOrDefault("RETRY", 0L),
                jobs.getOrDefault("COMPLETE", 0L), qdrant.pointCount(), qdrant.indexedVectorCount());
    }

    public AdminMemoryVectorIndexRebuildResponse requestRebuild() {
        String model = embeddingProperties.model();
        MemoryVectorIndexJobService.RebuildRequestResult result = jobService.requestRebuild(model);
        return new AdminMemoryVectorIndexRebuildResponse(Instant.now(clock), model,
                result.vectorRowsReset(), result.upsertJobsQueued(), "REBUILD_REQUESTED");
    }

    private Map<String, Long> jobCounts(List<Map<String, Object>> rows) {
        java.util.LinkedHashMap<String, Long> result = new java.util.LinkedHashMap<>();
        if (rows == null) return result;
        for (Map<String, Object> row : rows) {
            String status = text(row, "status");
            if (!status.isBlank()) result.put(status.toUpperCase(), number(row, "job_count"));
        }
        return result;
    }

    private String text(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).filter(value -> value != null).map(Object::toString)
                .findFirst().orElse("");
    }

    private long number(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).filter(Number.class::isInstance).map(Number.class::cast)
                .mapToLong(Number::longValue).findFirst().orElse(0L);
    }
}
