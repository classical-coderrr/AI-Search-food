package com.example.food.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class QdrantMemoryVectorIndexTest {
    private static final String COLLECTION_URL = "http://qdrant.test:6333/collections/memory_test";
    private static final String COLLECTION = """
            {"result":{"config":{"params":{"vectors":{"size":2,"distance":"Cosine"}}}}}
            """;

    private QdrantMemoryVectorProperties qdrantProperties;
    private MockRestServiceServer server;
    private QdrantMemoryVectorIndex index;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        qdrantProperties = new QdrantMemoryVectorProperties();
        qdrantProperties.setEnabled(true);
        qdrantProperties.setUrl("http://qdrant.test:6333");
        qdrantProperties.setCollection("memory_test");
        MemoryEmbeddingProperties embeddingProperties = new MemoryEmbeddingProperties();
        embeddingProperties.setDimensions(2);
        index = new QdrantMemoryVectorIndex(restTemplate, qdrantProperties, embeddingProperties);
    }

    @Test
    void createsMissingCollectionWithConfiguredDimensionsAndPayloadIndexes() {
        server.expect(requestTo(COLLECTION_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo(COLLECTION_URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vectors.size").value(2))
                .andExpect(jsonPath("$.vectors.distance").value("Cosine"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(COLLECTION_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(COLLECTION, MediaType.APPLICATION_JSON));
        expectPayloadIndex("user_id", "integer");
        expectPayloadIndex("embedding_model", "keyword");

        index.ensureCollection();
        index.ensureCollection();

        server.verify();
    }

    @Test
    void searchScopesQdrantQueryToTheUserAndModelAndRejectsForeignPayloads() {
        expectExistingCollection();
        server.expect(requestTo(COLLECTION_URL + "/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.filter.must[0].key").value("user_id"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value(17))
                .andExpect(jsonPath("$.filter.must[1].key").value("embedding_model"))
                .andExpect(jsonPath("$.filter.must[1].match.value").value("test-model"))
                .andRespond(withSuccess("""
                        {"result":{"points":[
                          {"payload":{"user_id":17,"embedding_model":"test-model","source_kind":"MEMORY_ITEM","source_id":9},"score":0.8},
                          {"payload":{"user_id":18,"embedding_model":"test-model","source_kind":"MEMORY_ITEM","source_id":10},"score":0.9},
                          {"payload":{"user_id":17,"embedding_model":"other-model","source_kind":"EPISODE","source_id":11},"score":0.7}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        index.ensureCollection();

        List<MemoryVectorMatch> matches = index.search(17L, "test-model", new float[]{1.0f, 0.0f}, 3);

        assertThat(matches).containsExactly(new MemoryVectorMatch("MEMORY_ITEM", 9L, 0.8));
        server.verify();
    }

    @Test
    void userDeletionUsesAUserScopedQdrantFilter() {
        expectExistingCollection();
        server.expect(requestTo(COLLECTION_URL + "/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.filter.must").isArray())
                .andExpect(jsonPath("$.filter.must[0].key").value("user_id"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value(17))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        index.ensureCollection();

        index.deleteAll(17L);

        server.verify();
    }

    @Test
    void collectionStatusReturnsOnlyOperationalCounts() {
        server.expect(requestTo(COLLECTION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result":{"status":"green","points_count":135,"indexed_vectors_count":0,
                          "config":{"params":{"vectors":{"size":2,"distance":"Cosine"}}}}}
                        """, MediaType.APPLICATION_JSON));

        QdrantMemoryVectorIndex.CollectionStatus status = index.inspectCollection();

        assertThat(status.state()).isEqualTo("GREEN");
        assertThat(status.available()).isTrue();
        assertThat(status.pointCount()).isEqualTo(135);
        assertThat(status.indexedVectorCount()).isZero();
        server.verify();
    }

    @Test
    void missingCollectionIsReportedWithoutCreatingIt() {
        server.expect(requestTo(COLLECTION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(NOT_FOUND));

        QdrantMemoryVectorIndex.CollectionStatus status = index.inspectCollection();

        assertThat(status.state()).isEqualTo("MISSING");
        assertThat(status.available()).isFalse();
        assertThat(status.pointCount()).isZero();
        server.verify();
    }

    @Test
    void countPointsUsesExactModelScopedFilter() {
        server.expect(requestTo(COLLECTION_URL + "/points/count"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.exact").value(true))
                .andExpect(jsonPath("$.filter.must[0].key").value("embedding_model"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value("test-model"))
                .andRespond(withSuccess("{\"result\":{\"count\":135}}", MediaType.APPLICATION_JSON));

        assertThat(index.countPointsByModel("test-model")).isEqualTo(135);
        server.verify();
    }

    @Test
    void identityLookupUsesDeterministicIdsAndAcceptsMatchingPayload() {
        MemoryEmbedding expected = expectedVector();
        String id = pointId(expected);
        server.expect(requestTo(COLLECTION_URL + "/points"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids[0]").value(id))
                .andExpect(jsonPath("$.with_payload").value(true))
                .andExpect(jsonPath("$.with_vector").value(false))
                .andRespond(withSuccess("""
                        {"result":[{"id":"%s","payload":{"user_id":17,"source_kind":"MEMORY_ITEM",
                        "source_id":9,"source_version":2,"embedding_model":"test-model"}}]}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        assertThat(index.findMissingPoints(List.of(expected))).isEmpty();
        server.verify();
    }

    @Test
    void identityLookupTreatsWrongVersionPayloadAsMissingEvenWhenPointIdExists() {
        MemoryEmbedding expected = expectedVector();
        String id = pointId(expected);
        server.expect(requestTo(COLLECTION_URL + "/points"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"result":[{"id":"%s","payload":{"user_id":17,"source_kind":"MEMORY_ITEM",
                        "source_id":9,"source_version":1,"embedding_model":"test-model"}}]}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        assertThat(index.findMissingPoints(List.of(expected))).containsExactly(expected);
        server.verify();
    }

    @Test
    void scrollReturnsModelScopedPointIdsAndContinuationOffset() {
        MemoryEmbedding expected = expectedVector();
        String id = pointId(expected);
        server.expect(requestTo(COLLECTION_URL + "/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.filter.must[0].key").value("embedding_model"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value("test-model"))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.with_vector").value(false))
                .andRespond(withSuccess("""
                        {"result":{"points":[{"id":"%s","payload":{"user_id":17,
                        "source_kind":"MEMORY_ITEM","source_id":9,"source_version":2,
                        "embedding_model":"test-model"}}],"next_page_offset":"next-id"}}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        QdrantMemoryVectorIndex.ModelPointPage page = index.scrollModelPointPage("test-model", null, 3);

        assertThat(page.points()).hasSize(1);
        assertThat(page.points().get(0).canonicalId()).isTrue();
        assertThat(page.nextOffset().asText()).isEqualTo("next-id");
        server.verify();
    }

    @Test
    void deletesOnlyRequestedOrphanPointIds() {
        server.expect(requestTo(COLLECTION_URL + "/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.points[0]").value("orphan-id"))
                .andRespond(withSuccess("{\"result\":{\"status\":\"completed\"}}", MediaType.APPLICATION_JSON));

        index.deletePointsById(List.of("orphan-id"));

        server.verify();
    }

    @Test
    void rejectsInvalidDimensionsBeforeAnyRemoteWrite() {
        assertThatThrownBy(() -> index.upsert(new MemoryVectorDocument(17L, "MEMORY_ITEM", 9L,
                "INGREDIENT_PREFERENCE", 1, "test-model", new float[]{1.0f})))
                .isInstanceOf(IllegalArgumentException.class);

        server.verify();
    }

    private void expectExistingCollection() {
        server.expect(requestTo(COLLECTION_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(COLLECTION, MediaType.APPLICATION_JSON));
        expectPayloadIndex("user_id", "integer");
        expectPayloadIndex("embedding_model", "keyword");
    }

    private MemoryEmbedding expectedVector() {
        MemoryEmbedding row = new MemoryEmbedding();
        row.setUserId(17L);
        row.setSourceKind("MEMORY_ITEM");
        row.setSourceId(9L);
        row.setSourceVersion(2);
        row.setEmbeddingModel("test-model");
        return row;
    }

    private String pointId(MemoryEmbedding row) {
        String key = row.getUserId() + "|" + row.getSourceKind() + "|" + row.getSourceId() + "|"
                + row.getEmbeddingModel();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void expectPayloadIndex(String field, String schema) {
        server.expect(requestTo(COLLECTION_URL + "/index?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.field_name").value(field))
                .andExpect(jsonPath("$.field_schema").value(schema))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }
}
