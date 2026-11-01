package dev.learning.agent.tool;

import dev.learning.agent.support.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Shared field validation; each registered tool owns its typed decoding. */
public final class ToolInputDecoder {
    private ToolInputDecoder() {}
    public static String oneString(String arguments, String field, int maximum) throws IOException {
        JsonNode node = StrictJson.object(arguments);
        StrictJson.fields(node, field);
        String value = StrictJson.text(node, field);
        if (value.length() > maximum) throw new IOException("tool argument too long: " + field);
        return value;
    }
}
