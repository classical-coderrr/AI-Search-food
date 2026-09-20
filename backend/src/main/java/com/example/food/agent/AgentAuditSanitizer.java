package com.example.food.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Redacts credentials and direct identifiers before an Agent step is audited. */
public final class AgentAuditSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "token", "accesstoken", "refreshtoken", "password",
            "secret", "authorization", "accesskey", "accesskeyid", "accesskeysecret",
            "phone", "mobile", "jwt"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+", Pattern.MULTILINE);

    private AgentAuditSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            JsonNode root = MAPPER.readTree(value);
            redact(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ignored) {
            return BEARER.matcher(value).replaceAll("$1[REDACTED]");
        }
    }

    private static void redact(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    object.put(field.getKey(), "[REDACTED]");
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            array.forEach(AgentAuditSanitizer::redact);
        }
    }

    private static boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.replace("-", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
    }
}
