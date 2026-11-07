package dev.learning.agent.observability;

import dev.learning.agent.support.json.StrictJson;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicit preview allowlist. No full request, history, exception, Bash or search body is serialized. */
public final class TraceContentPolicy {
    public String preview(String text, boolean restricted) {
        if (restricted || text == null) {
            return null;
        }

        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        int at = 0;
        while (at < text.length()) {
            int point = text.codePointAt(at);
            int width = point < 32 ? 6 : point == '"' || point == '\\' ? 2
                    : new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > 4064) {
                break;
            }
            kept.appendCodePoint(point);
            bytes += width;
            at += Character.charCount(point);
        }
        return at == text.length() ? text : kept + " [truncated]";
    }

    public ToolInput toolInput(String name, String arguments, boolean restricted) {
        if (restricted || !List.of("calculator", "current_weather", "weather_forecast").contains(name)) {
            return new ToolInput(name, null, null, null, null, null);
        }

        try {
            var node = StrictJson.object(arguments);
            if (name.equals("calculator")) {
                String expression = preview(node.path("expression").asText(null), false);
                return new ToolInput(name, expression, null, null, null, null);
            }

            String city = field(node.path("city").asText(null), 200);
            String countryCode = field(node.path("country_code").asText(null), 2);
            String admin1 = field(node.path("admin1").asText(null), 200);
            Integer days = node.has("days") && node.get("days").isInt() ? node.get("days").intValue() : null;
            return new ToolInput(name, null, city, countryCode, admin1, days);
        } catch (Exception ignored) {
            return new ToolInput(name, null, null, null, null, null);
        }
    }

    private String field(String value, int limit) {
        if (value == null || value.length() > limit) {
            return null;
        }
        return preview(value, false);
    }

    public record ToolInput(String tool, String expression, String city, String countryCode, String admin1, Integer days) {}
}
