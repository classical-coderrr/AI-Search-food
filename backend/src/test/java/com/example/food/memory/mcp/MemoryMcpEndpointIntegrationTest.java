package com.example.food.memory.mcp;

import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.example.food.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.memory.mcp.enabled=true")
@ActiveProfiles("test")
class MemoryMcpEndpointIntegrationTest {
    private static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endpointRejectsUnauthenticatedRequests() {
        ResponseEntity<String> response = post("", """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"memory-mcp-test","version":"1.0"}}}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedUserCanDiscoverToolsAndReadOwnProfileWithoutUserIdArgument() throws Exception {
        TestUser user = createUser();
        String token = user.token();

        ResponseEntity<String> initialized = post(token, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{
                    "elicitation":{"form":{}}},
                  "clientInfo":{"name":"memory-mcp-test","version":"1.0"}}}
                """);

        assertThat(initialized.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = initialized.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> initializedNotification = post(token, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, sessionId);
        assertThat(initializedNotification.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> toolsResponse = post(token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sessionId);
        assertThat(toolsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode toolsBody = parseMcpBody(toolsResponse);
        List<String> toolNames = toolsBody.at("/result/tools").findValuesAsText("name");
        assertThat(toolNames).contains("memory.search", "memory.profile.get", "memory.episodes.list",
                "memory.recipe.history", "memory.skill.get", "memory.episode.save",
                "memory.preference.update", "memory.feedback.record");
        JsonNode profileTool = null;
        for (JsonNode candidate : toolsBody.at("/result/tools")) {
            if ("memory.profile.get".equals(candidate.path("name").asText())) {
                profileTool = candidate;
                break;
            }
        }
        assertThat(profileTool).isNotNull();
        assertThat(profileTool.path("inputSchema").path("properties").has("userId")).isFalse();

        ResponseEntity<String> profileResponse = post(token, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"memory.profile.get","arguments":{}}}
                """, sessionId);
        assertThat(profileResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode profileBody = parseMcpBody(profileResponse);
        assertThat(profileBody.at("/result/isError").asBoolean()).isFalse();
        String profileJson = profileBody.at("/result/content/0/text").asText();
        assertThat(profileJson).contains("editableMemories");
    }

    @Test
    void writeToolDoesNotFallBackWhenClientCannotAskForUserConfirmation() throws Exception {
        TestUser user = createUser();
        String sessionId = initialize(user.token(), false);
        ResponseEntity<String> response = post(user.token(), """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"memory.episode.save","arguments":{
                    "candidateType":"INGREDIENT_PREFERENCE","entity":"鸡胸肉",
                    "preference":"LIKE","evidence":"我喜欢鸡胸肉",
                    "idempotencyKey":"mcp-confirm-required"}}}
                """, sessionId);

        JsonNode result = parseMcpBody(response);
        assertThat(result.at("/result/isError").asBoolean()).isTrue();
        assertThat(result.at("/result/content/0/text").asText()).contains("确认弹窗");
        Integer createdEpisodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_episodes WHERE user_id = ? AND source_type = 'AGENT_DECLARATION'",
                Integer.class, user.id());
        assertThat(createdEpisodes).isZero();
    }

    @Test
    void mcpSessionCannotBeReusedByAnotherAuthenticatedUser() throws Exception {
        TestUser owner = createUser();
        TestUser otherUser = createUser();
        String sessionId = initialize(owner.token(), true);
        String otherSessionId = initialize(otherUser.token(), false);
        ResponseEntity<String> ownSessionResponse = post(otherUser.token(),
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}", otherSessionId);
        assertThat(ownSessionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = post(otherUser.token(),
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}", sessionId);

        assertThat(response.getStatusCode()).as("cross-account response body: %s", response.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> ownerResponse = post(owner.token(),
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}", sessionId);
        assertThat(ownerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlySessionOwnerCanDeleteSessionAndDeletedSessionCannotBeReused() throws Exception {
        TestUser owner = createUser();
        TestUser otherUser = createUser();
        String sessionId = initialize(owner.token(), false);

        ResponseEntity<String> otherDelete = delete(otherUser.token(), sessionId);
        assertThat(otherDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> ownerDelete = delete(owner.token(), sessionId);
        assertThat(ownerDelete.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> afterDelete = post(owner.token(),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}", sessionId);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> post(String token, String body) {
        return post(token, body, null);
    }

    private TestUser createUser() {
        String phone = "139" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000));
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "MCP 集成测试用户");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        String token = jwtService.generateToken(new AuthPrincipal(userId, phone, AppRole.USER, 0L));
        return new TestUser(userId, token);
    }

    private String initialize(String token, boolean supportsElicitation) throws Exception {
        String capabilities = supportsElicitation ? "\"elicitation\":{\"form\":{}}" : "";
        ResponseEntity<String> initialized = post(token, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{%s},
                  "clientInfo":{"name":"memory-mcp-test","version":"1.0"}}}
                """.formatted(capabilities));
        assertThat(initialized.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = initialized.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        ResponseEntity<String> notification = post(token,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        assertThat(notification.getStatusCode().is2xxSuccessful()).isTrue();
        return sessionId;
    }

    private ResponseEntity<String> post(String token, String body, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        if (!token.isBlank()) {
            headers.setBearerAuth(token);
        }
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        return restTemplate.exchange("http://localhost:" + port + MemoryMcpServerConfiguration.ENDPOINT,
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> delete(String token, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.setBearerAuth(token);
        headers.set("Mcp-Session-Id", sessionId);
        return restTemplate.exchange("http://localhost:" + port + MemoryMcpServerConfiguration.ENDPOINT,
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parseMcpBody(ResponseEntity<String> response) throws Exception {
        String body = response.getBody();
        if (body != null && body.contains("data:")) {
            body = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).trim())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(body);
        }
        return objectMapper.readTree(body);
    }

    private record TestUser(Long id, String token) { }
}
