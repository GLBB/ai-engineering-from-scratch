package dev.learning.agent.support.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** JSON trees exist only at parsing boundaries; runners retain typed records. */
public final class StrictJson {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private StrictJson() {}

    public static JsonNode object(String text) throws IOException {
        if (text == null || text.isBlank() || text.length() > 16_000) throw new IOException("invalid JSON length");
        JsonNode node = MAPPER.readTree(text);
        if (node == null || !node.isObject()) throw new IOException("expected a JSON object");
        return node;
    }

    public static void fields(JsonNode node, String... allowed) throws IOException {
        Set<String> names = Set.of(allowed);
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!names.contains(name)) throw new IOException("unknown field: " + name);
        }
    }

    public static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("expected a nonblank string: " + field);
        }
        return value.asText();
    }

    public static List<String> strings(JsonNode root, String field) throws IOException {
        JsonNode values = root.get(field);
        if (values == null || !values.isArray()) throw new IOException(field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IOException("invalid " + field);
            if (result.contains(value.asText())) throw new IOException("duplicate " + field);
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalArgumentException("cannot encode typed value", e); }
    }
}
