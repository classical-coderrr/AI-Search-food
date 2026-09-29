package com.example.food.memory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Qdrant ANN index. MySQL remains the source of truth and the retrieval fallback. */
@Component
public class QdrantMemoryVectorIndex {
    public record CollectionStatus(String state, boolean available, long pointCount,
                                   long indexedVectorCount) { }
    public record ModelPointIdentity(String id, Long userId, String sourceKind, Long sourceId,
                                     Integer sourceVersion, boolean canonicalId) { }
    public record ModelPointPage(List<ModelPointIdentity> points, JsonNode nextOffset) { }

    private final RestTemplate restTemplate;
    private final QdrantMemoryVectorProperties properties;
    private final MemoryEmbeddingProperties embeddingProperties;
    private volatile long nextCollectionCheckNanos;

    @Autowired
    public QdrantMemoryVectorIndex(RestTemplateBuilder builder, QdrantMemoryVectorProperties properties,
                                   MemoryEmbeddingProperties embeddingProperties) {
        this(builder.setConnectTimeout(positiveOr(properties.connectTimeout(), Duration.ofSeconds(2)))
                        .setReadTimeout(positiveOr(properties.readTimeout(), Duration.ofSeconds(4))).build(),
                properties, embeddingProperties);
    }

    QdrantMemoryVectorIndex(RestTemplate restTemplate, QdrantMemoryVectorProperties properties,
                            MemoryEmbeddingProperties embeddingProperties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public boolean collectionExists() {
        if (!enabled()) return false;
        try {
            validateCollection(restTemplate.getForObject(collectionUrl(), JsonNode.class));
            return true;
        } catch (HttpClientErrorException.NotFound missing) {
            nextCollectionCheckNanos = 0;
            return false;
        }
    }

    public CollectionStatus inspectCollection() {
        if (!enabled()) return new CollectionStatus("DISABLED", false, 0, 0);
        try {
            JsonNode collection = restTemplate.getForObject(collectionUrl(), JsonNode.class);
            validateCollection(collection);
            JsonNode result = collection.path("result");
            String state = result.path("status").asText("UNKNOWN").toUpperCase();
            boolean available = !"RED".equals(state) && !"GREY".equals(state);
            return new CollectionStatus(state, available,
                    Math.max(0, result.path("points_count").asLong(0)),
                    Math.max(0, result.path("indexed_vectors_count").asLong(0)));
        } catch (HttpClientErrorException.NotFound missing) {
            return new CollectionStatus("MISSING", false, 0, 0);
        } catch (RuntimeException unavailable) {
            return new CollectionStatus("UNAVAILABLE", false, 0, 0);
        }
    }

    public long countPointsByModel(String model) {
        if (!enabled() || !StringUtils.hasText(model)) return 0;
        Map<String, Object> filter = Map.of("must", List.of(match("embedding_model", model)));
        JsonNode response = restTemplate.postForObject(collectionUrl() + "/points/count",
                jsonEntity(Map.of("filter", filter, "exact", true)), JsonNode.class);
        long count = response == null ? -1 : response.path("result").path("count").asLong(-1);
        if (count < 0) throw new IllegalStateException("Qdrant 返回了无效的记忆向量计数");
        return count;
    }

    public List<MemoryEmbedding> findMissingPoints(List<MemoryEmbedding> expected) {
        if (!enabled() || expected == null || expected.isEmpty()) return List.of();
        Map<String, MemoryEmbedding> requested = new LinkedHashMap<>();
        String model = null;
        for (MemoryEmbedding row : expected) {
            if (row == null || row.getUserId() == null || row.getUserId() <= 0
                    || !StringUtils.hasText(row.getSourceKind()) || row.getSourceId() == null
                    || row.getSourceId() <= 0 || !StringUtils.hasText(row.getEmbeddingModel())
                    || row.getSourceVersion() == null) {
                throw new IllegalArgumentException("Qdrant 一致性检查的记忆标识无效");
            }
            if (model == null) model = row.getEmbeddingModel();
            if (!model.equals(row.getEmbeddingModel())) {
                throw new IllegalArgumentException("Qdrant 一致性检查不能混用不同向量模型");
            }
            requested.put(pointId(row.getUserId(), row.getSourceKind(), row.getSourceId(), row.getEmbeddingModel()), row);
        }
        JsonNode response = restTemplate.postForObject(collectionUrl() + "/points", jsonEntity(Map.of(
                "ids", List.copyOf(requested.keySet()), "with_payload", true, "with_vector", false)), JsonNode.class);
        JsonNode points = response == null ? null : response.path("result");
        if (points == null || !points.isArray()) {
            throw new IllegalStateException("Qdrant 返回了无效的记忆点查询结果");
        }
        LinkedHashSet<String> validPointIds = new LinkedHashSet<>();
        for (JsonNode point : points) {
            String id = point.path("id").asText("");
            MemoryEmbedding row = requested.get(id);
            if (row != null && payloadMatches(row, point.path("payload"))) validPointIds.add(id);
        }
        return requested.entrySet().stream()
                .filter(entry -> !validPointIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public ModelPointPage scrollModelPointPage(String model, JsonNode offset, int limit) {
        if (!enabled() || !StringUtils.hasText(model)) return new ModelPointPage(List.of(), null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("filter", Map.of("must", List.of(match("embedding_model", model))));
        request.put("limit", Math.max(1, Math.min(limit, 1000)));
        request.put("with_payload", List.of("user_id", "source_kind", "source_id", "source_version",
                "embedding_model"));
        request.put("with_vector", false);
        if (offset != null && !offset.isNull()) request.put("offset", offset);

        JsonNode response = restTemplate.postForObject(collectionUrl() + "/points/scroll", jsonEntity(request),
                JsonNode.class);
        JsonNode result = response == null ? null : response.path("result");
        JsonNode points = result == null ? null : result.path("points");
        if (points == null || !points.isArray()) {
            throw new IllegalStateException("Qdrant 返回了无效的记忆点分页结果");
        }
        List<ModelPointIdentity> identities = new ArrayList<>(points.size());
        for (JsonNode point : points) {
            String id = point.path("id").asText("");
            JsonNode payload = point.path("payload");
            long rawUserId = payload.path("user_id").asLong(-1);
            long rawSourceId = payload.path("source_id").asLong(-1);
            int rawSourceVersion = payload.path("source_version").asInt(-1);
            String sourceKind = payload.path("source_kind").asText("");
            String pointModel = payload.path("embedding_model").asText("");
            Long userId = rawUserId > 0 ? rawUserId : null;
            Long sourceId = rawSourceId > 0 ? rawSourceId : null;
            Integer sourceVersion = rawSourceVersion >= 0 ? rawSourceVersion : null;
            boolean supportedSource = List.of("MEMORY_ITEM", "EPISODE").contains(sourceKind);
            boolean canonicalId = userId != null && sourceId != null && sourceVersion != null && supportedSource
                    && model.equals(pointModel)
                    && id.equals(pointId(userId, sourceKind, sourceId, model));
            identities.add(new ModelPointIdentity(id, userId, sourceKind, sourceId, sourceVersion, canonicalId));
        }
        JsonNode nextOffset = result.path("next_page_offset");
        return new ModelPointPage(List.copyOf(identities), nextOffset.isMissingNode() || nextOffset.isNull()
                ? null : nextOffset);
    }

    public void deletePointsById(List<String> pointIds) {
        if (!enabled() || pointIds == null || pointIds.isEmpty()) return;
        restTemplate.exchange(collectionUrl() + "/points/delete?wait=true", HttpMethod.POST,
                jsonEntity(Map.of("points", List.copyOf(pointIds))), JsonNode.class);
    }

    public void ensureCollection() {
        if (!enabled()) return;
        long now = System.nanoTime();
        if (now < nextCollectionCheckNanos) return;
        synchronized (this) {
            now = System.nanoTime();
            if (now < nextCollectionCheckNanos) return;
            ensureCollectionNow();
            nextCollectionCheckNanos = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        }
    }

    private void ensureCollectionNow() {
        String url = collectionUrl();
        try {
            JsonNode existing = restTemplate.getForObject(url, JsonNode.class);
            validateCollection(existing);
        } catch (HttpClientErrorException.NotFound missing) {
            Map<String, Object> body = Map.of("vectors", Map.of(
                    "size", embeddingProperties.dimensions(), "distance", "Cosine"));
            try {
                restTemplate.exchange(url, HttpMethod.PUT, jsonEntity(body), JsonNode.class);
            } catch (HttpClientErrorException.Conflict alreadyCreated) {
                // Another application instance created the shared collection.
            }
            validateCollection(restTemplate.getForObject(url, JsonNode.class));
        }
        ensurePayloadIndex("user_id", "integer");
        ensurePayloadIndex("embedding_model", "keyword");
    }

    public void upsert(MemoryVectorDocument document) {
        if (!enabled() || document == null || document.userId() == null || document.userId() <= 0
                || document.sourceId() == null || document.sourceId() <= 0
                || !StringUtils.hasText(document.sourceKind()) || !StringUtils.hasText(document.model())
                || document.embedding() == null || document.embedding().length != embeddingProperties.dimensions()) {
            throw new IllegalArgumentException("Qdrant 向量索引数据无效");
        }
        for (float component : document.embedding()) {
            if (!Float.isFinite(component)) throw new IllegalArgumentException("Qdrant 向量包含无效数值");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", document.userId());
        payload.put("source_kind", document.sourceKind());
        payload.put("source_id", document.sourceId());
        payload.put("source_version", document.sourceVersion());
        payload.put("memory_type", document.memoryType());
        payload.put("embedding_model", document.model());
        Map<String, Object> point = Map.of(
                "id", pointId(document.userId(), document.sourceKind(), document.sourceId(), document.model()),
                "vector", document.embedding(),
                "payload", payload);
        restTemplate.exchange(collectionUrl() + "/points?wait=true", HttpMethod.PUT,
                jsonEntity(Map.of("points", List.of(point))), JsonNode.class);
    }

    public List<MemoryVectorMatch> search(Long userId, String model, float[] queryVector, int limit) {
        if (!enabled() || userId == null || userId <= 0 || !StringUtils.hasText(model)
                || queryVector == null || queryVector.length != embeddingProperties.dimensions() || limit <= 0) {
            return List.of();
        }
        Map<String, Object> filter = Map.of("must", List.of(
                match("user_id", userId), match("embedding_model", model)));
        Map<String, Object> request = Map.of("query", queryVector, "filter", filter,
                "limit", Math.max(1, Math.min(limit, 1000)), "with_payload", true, "with_vector", false);
        JsonNode response = restTemplate.postForObject(collectionUrl() + "/points/query", jsonEntity(request),
                JsonNode.class);
        JsonNode points = response == null ? null : response.path("result").path("points");
        if (points == null || !points.isArray()) return List.of();
        List<MemoryVectorMatch> matches = new ArrayList<>(points.size());
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            long pointUserId = payload.path("user_id").asLong(-1);
            String sourceKind = payload.path("source_kind").asText("");
            long sourceId = payload.path("source_id").asLong(-1);
            String pointModel = payload.path("embedding_model").asText("");
            double score = point.path("score").asDouble(Double.NaN);
            if (pointUserId != userId || !model.equals(pointModel) || sourceId <= 0
                    || !List.of("MEMORY_ITEM", "EPISODE").contains(sourceKind) || !Double.isFinite(score)) continue;
            matches.add(new MemoryVectorMatch(sourceKind, sourceId, Math.max(0, Math.min(1, score))));
        }
        return List.copyOf(matches);
    }

    public void deleteSource(Long userId, String sourceKind, Long sourceId) {
        if (!enabled() || userId == null || userId <= 0 || !StringUtils.hasText(sourceKind)
                || sourceId == null || sourceId <= 0) return;
        deleteByFilter(Map.of("must", List.of(match("user_id", userId), match("source_kind", sourceKind),
                match("source_id", sourceId))));
    }

    public void deleteAll(Long userId) {
        if (!enabled() || userId == null || userId <= 0) return;
        deleteByFilter(Map.of("must", List.of(match("user_id", userId))));
    }

    private void deleteByFilter(Map<String, Object> filter) {
        restTemplate.exchange(collectionUrl() + "/points/delete?wait=true", HttpMethod.POST,
                jsonEntity(Map.of("filter", filter)), JsonNode.class);
    }

    private void ensurePayloadIndex(String field, String schema) {
        Map<String, Object> request = Map.of("field_name", field, "field_schema", schema);
        try {
            restTemplate.exchange(collectionUrl() + "/index?wait=true", HttpMethod.PUT,
                    jsonEntity(request), JsonNode.class);
        } catch (HttpClientErrorException.Conflict alreadyExists) {
            // The index already exists on a shared collection.
        }
    }

    private void validateCollection(JsonNode response) {
        JsonNode vectors = response == null ? null : response.path("result").path("config")
                .path("params").path("vectors");
        int size = vectors == null ? -1 : vectors.path("size").asInt(-1);
        String distance = vectors == null ? "" : vectors.path("distance").asText("");
        if (size != embeddingProperties.dimensions() || !"Cosine".equalsIgnoreCase(distance)) {
            throw new IllegalStateException("Qdrant 集合向量维度或距离配置与当前模型不匹配");
        }
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(properties.apiKey())) headers.set("api-key", properties.apiKey().trim());
        return new HttpEntity<>(body, headers);
    }

    private String collectionUrl() {
        String collection = properties.collection() == null ? "" : properties.collection().trim();
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalStateException("Qdrant 集合名称配置无效");
        }
        String base = properties.url() == null ? "" : properties.url().trim().replaceAll("/+$", "");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalStateException("Qdrant 服务地址配置无效");
        }
        return UriComponentsBuilder.fromHttpUrl(base).pathSegment("collections", collection).build().toUriString();
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private boolean payloadMatches(MemoryEmbedding row, JsonNode payload) {
        return payload.path("user_id").asLong(-1) == row.getUserId()
                && row.getSourceKind().equals(payload.path("source_kind").asText(""))
                && payload.path("source_id").asLong(-1) == row.getSourceId()
                && payload.path("source_version").asInt(-1) == row.getSourceVersion()
                && row.getEmbeddingModel().equals(payload.path("embedding_model").asText(""));
    }

    private String pointId(Long userId, String sourceKind, Long sourceId, String model) {
        String key = userId + "|" + sourceKind + "|" + sourceId + "|" + model;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
