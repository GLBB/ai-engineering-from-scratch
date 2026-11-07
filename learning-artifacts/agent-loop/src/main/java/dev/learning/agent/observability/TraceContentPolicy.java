package dev.learning.agent.observability;

import dev.learning.agent.support.json.StrictJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Explicit preview allowlist. No full request, history, exception, Bash or search body is serialized. */
public final class TraceContentPolicy {
    private final boolean learning;
    private final List<String> secrets;

    public TraceContentPolicy(boolean learning, Map<String, String> environment) {
        this.learning = learning;
        secrets = environment.entrySet().stream()
                .filter(e -> e.getKey().matches(".*(KEY|TOKEN|SECRET|PASSWORD).*"))
                .map(Map.Entry::getValue).filter(value -> value != null && value.length() >= 6).toList();
    }
    public String mode() { return learning ? "learning" : "metadata"; }
    public String preview(String text, boolean restricted) {
        if (!learning || restricted || text == null) return null;
        String safe = text;
        for (String secret : secrets) safe = safe.replace(secret, "[REDACTED]");
        safe = safe.replaceAll("(?i)(?:Bearer\\s+|(?:sk|pk)-(?:lf-)?)[A-Za-z0-9_./+=-]{8,}", "[REDACTED]");
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        int at = 0;
        while (at < safe.length()) {
            int point = safe.codePointAt(at);
            int width = point < 32 ? 6 : point == '"' || point == '\\' ? 2
                    : new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > 4064) break;
            kept.appendCodePoint(point);
            bytes += width;
            at += Character.charCount(point);
        }
        return at == safe.length() ? safe : kept + " [truncated]";
    }
    public ToolInput toolInput(String name, String arguments, boolean restricted) {
        if (!learning || restricted || !List.of("calculator", "current_weather", "weather_forecast").contains(name))
            return new ToolInput(name, null, null, null, null, null);
        try {
            var node = StrictJson.object(arguments);
            if (name.equals("calculator")) return new ToolInput(name, preview(node.path("expression").asText(null), false), null, null, null, null);
            return new ToolInput(name, null, field(node.path("city").asText(null), 200), field(node.path("country_code").asText(null), 2),
                    field(node.path("admin1").asText(null), 200), node.has("days") && node.get("days").isInt() ? node.get("days").intValue() : null);
        } catch (Exception ignored) { return new ToolInput(name, null, null, null, null, null); }
    }
    private String field(String value, int limit) { return value == null || value.length() > limit ? null : preview(value, false); }
    public record ToolInput(String tool, String expression, String city, String countryCode, String admin1, Integer days) {}
}
