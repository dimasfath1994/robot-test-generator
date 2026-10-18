package com.hsbc.automation.payload;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class CompanyPayloadMapping {
    private static final Set<String> SOURCES = new HashSet<>(Arrays.asList("eimId", "engagementId", "username",
        "repositoryUrl", "testSuitePath", "itidspProjectId", "crNumber", "now"));
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("string", "char", "integer", "double",
            "decimal", "float", "boolean", "array", "list", "object", "datetime"));
    private final ObjectMapper mapper = new ObjectMapper().enable(
        com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonNode parse(String json) throws IOException {
        if (json == null || json.length() > 262144) {
            throw new IOException("Payload mapping must be at most 256 KB.");
        }
        JsonNode config = mapper.readTree(json);
        if (config == null || !config.isObject() || !config.path("version").isIntegralNumber()
                || config.path("version").asInt() != 1 || !config.path("payload").isObject()) {
            throw new IOException("Mapping requires version: 1 and an object payload.");
        }
        validate(config.path("payload"), 0);
        return config;
    }

    public JsonNode render(JsonNode config, JsonNode context, Clock clock) throws IOException {
        return resolve(config.path("payload"), context, clock, 0);
    }

    private void validate(JsonNode node, int depth) throws IOException {
        if (depth > 24) {
            throw new IOException("Payload mapping nesting exceeds 24 levels.");
        }
        if (node.isObject() && node.has("$source")) {
            String source = node.path("$source").asText();
            String type = node.path("$type").asText("string");
            if (!SOURCES.contains(source) || !TYPES.contains(type)) {
                throw new IOException("Unknown payload source or type: " + source + " / " + type);
            }
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!Arrays.asList("$source", "$type", "$default", "$omitIfEmpty", "$required", "$format", "$zone").contains(name)) {
                    throw new IOException("Unknown mapping option: " + name);
                }
            }
            for (String flag : Arrays.asList("$omitIfEmpty", "$required")) {
                if (node.has(flag) && !node.get(flag).isBoolean()) {
                    throw new IOException(flag + " must be boolean.");
                }
            }
            try {
                if ("now".equals(source) || "datetime".equals(type)) {
                    DateTimeFormatter.ofPattern(node.path("$format").asText("yyyy-MM-dd HH:mm:ss"));
                    ZoneId.of(node.path("$zone").asText("Asia/Kolkata"));
                }
                if (node.has("$default") && !node.get("$default").isNull()) {
                    convert(node.get("$default"), type, node);
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid default or datetime format for " + source + ".");
            }
        } else if (node.isContainerNode()) {
            for (JsonNode child : node) {
                validate(child, depth + 1);
            }
        }
    }

    private JsonNode resolve(JsonNode node, JsonNode context, Clock clock, int depth) throws IOException {
         if (depth > 24) {
            throw new IOException("Payload mapping nesting exceeds 24 levels.");
        }
        if (node.isObject() && node.has("$source")) {
            String source = node.path("$source").asText();
            JsonNode value = context.path(source);
            if ("now".equals(source)) {
                value = JsonNodeFactory.instance.textNode(ZonedDateTime.now(clock.withZone(
                    ZoneId.of(node.path("$zone").asText("Asia/Kolkata"))))
                .format(DateTimeFormatter.ofPattern(node.path("$format").asText("yyyy-MM-dd HH:mm:ss"))));
            }
            if (empty(value) && node.has("$default")) {
                value = node.get("$default");
            }
            if (empty(value)) {
                if (node.path("$required").asBoolean(false)) {
                    throw new IOException("Required payload source is empty: " + source);
                }
                if (node.path("$omitIfEmpty").asBoolean(false)) {
                    return null;
                }
                return JsonNodeFactory.instance.nullNode();
            }
            try {
                return convert(value, node.path("$type").asText("string"), node);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid payload value type for source " + source + ".");
            }
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                JsonNode resolved = resolve(field.getValue(), context, clock, depth + 1);
                if (resolved != null) {
                    result.set(field.getKey(), resolved);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode resolved = resolve(item, context, clock, depth + 1);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return result;
        }
        return node.deepCopy();
    }

    private boolean empty(JsonNode value) {
        return value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().trim().isEmpty());
    }

    private JsonNode convert(JsonNode value, String type, JsonNode rule) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        switch (type) {
            case "string":
                if (value.isContainerNode()) { throw new IllegalArgumentException(); }
                return factory.textNode(value.asText());
            case "char":
                String character = value.asText();
                if (character.codePointCount(0, character.length()) != 1) { throw new IllegalArgumentException(); }
                return factory.textNode(character);
            case "integer": return factory.numberNode(new BigInteger(value.asText()));
            case "decimal":
            case "float":
            case "double": return factory.numberNode(new BigDecimal(value.asText()));
            case "boolean":
                if (!"true".equals(value.asText()) && !"false".equals(value.asText())) { throw new IllegalArgumentException(); }
                return factory.booleanNode(Boolean.parseBoolean(value.asText()));
            case "array":
            case "list":
                if (!value.isArray()) {throw new IllegalArgumentException(); }
                return value.deepCopy();
            case "datetime":
                DateTimeFormatter.ofPattern(rule.path("$format").asText("yyyy-MM-dd HH:mm:ss")).parse(value.asText());
                return factory.textNode(value.asText());
            default: throw new IllegalArgumentException();
        }
    }
}