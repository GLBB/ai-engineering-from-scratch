package dev.learning.agent.core.rewoo;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learning.agent.core.StrictJson;
import dev.learning.agent.core.planning.PlanValidationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RewooPlanParser {
    private RewooPlanParser() {}
    public static RewooPlan parse(String text) throws IOException {
        JsonNode root = StrictJson.object(text);
        StrictJson.fields(root, "version", "steps", "answerEvidence");
        if (!root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt() || root.path("version").asInt() != 1) {
            throw new PlanValidationException("unsupported plan version");
        }
        if (!root.path("steps").isArray()) throw new PlanValidationException("steps must be an array");
        if (root.path("steps").size() > 8) throw new PlanValidationException("plan exceeds 8 nodes");
        List<RewooPlan.Step> steps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            if (!step.isObject()) throw new PlanValidationException("step must be an object");
            StrictJson.fields(step, "id", "purpose", "tool", "arguments");
            String tool = StrictJson.text(step, "tool");
            steps.add(new RewooPlan.Step(StrictJson.text(step, "id"), StrictJson.text(step, "purpose"),
                    StrictJson.toolInput(tool, StrictJson.write(step.get("arguments")))));
        }
        return new RewooPlan(1, steps, strings(root, "answerEvidence"));
    }
    public static List<String> strings(JsonNode root, String field) throws IOException {
        JsonNode values = root.get(field);
        if (values == null || !values.isArray()) throw new PlanValidationException(field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw new PlanValidationException("invalid " + field);
            if (result.contains(value.asText())) throw new PlanValidationException("duplicate " + field);
            result.add(value.asText());
        }
        return List.copyOf(result);
    }
}
