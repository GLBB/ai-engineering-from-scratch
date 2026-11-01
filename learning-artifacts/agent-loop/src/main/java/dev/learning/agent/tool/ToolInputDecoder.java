package dev.learning.agent.tool;

import dev.learning.agent.support.json.StrictJson;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Decodes registered tool arguments; JSON syntax checks stay in StrictJson. */
public final class ToolInputDecoder {
    private ToolInputDecoder() {}

    public static ToolInput decode(String tool, String arguments) throws IOException {
        JsonNode node = StrictJson.object(arguments);
        String field = switch (tool) {
            case "calculator" -> "expression";
            case "search" -> "query";
            case "bash" -> "command";
            default -> throw new IOException("unknown tool " + tool);
        };
        StrictJson.fields(node, field);
        String value = StrictJson.text(node, field);
        int max = switch (tool) { case "calculator" -> 200; case "search" -> 500; default -> 2000; };
        if (value.length() > max) throw new IOException("tool argument too long: " + field);
        return switch (tool) {
            case "calculator" -> new CalculatorInput(value);
            case "search" -> new SearchInput(value);
            default -> new BashInput(value);
        };
    }
}
