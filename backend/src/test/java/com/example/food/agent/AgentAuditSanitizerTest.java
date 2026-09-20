package com.example.food.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAuditSanitizerTest {

    @Test
    void redactsCredentialsAndDirectIdentifiersButKeepsRoutingMetadata() {
        String sanitized = AgentAuditSanitizer.sanitize("{\"token\":\"jwt-value\",\"phone\":\"13800000000\",\"intent\":\"SAVE_RECIPE\"}");

        assertThat(sanitized).contains("[REDACTED]");
        assertThat(sanitized).doesNotContain("jwt-value", "13800000000");
        assertThat(sanitized).contains("SAVE_RECIPE");
    }

    @Test
    void masksBearerTokenInNonJsonAuditText() {
        String sanitized = AgentAuditSanitizer.sanitize("Authorization: Bearer abc.def.ghi");

        assertThat(sanitized).isEqualTo("Authorization: Bearer [REDACTED]");
    }
}
