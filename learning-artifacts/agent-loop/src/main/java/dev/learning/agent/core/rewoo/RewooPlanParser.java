package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.planning.PlanValidationException;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.ToolRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RewooPlanParser {
    private RewooPlanParser() {
    }

    public static RewooPlan parse(String text, ToolRegistry tools) throws IOException {
        JsonNode root = StrictJson.object(text);
        StrictJson.fields(root, "version", "steps", "answerEvidence");
        if (!root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt()
            || root.path("version").asInt() != 1) {
            throw new PlanValidationException("unsupported plan version");
        }
        if (!root.path("steps").isArray()) {
            throw new PlanValidationException("steps must be an array");
        }
        if (root.path("steps").size() > 8) {
            throw new PlanValidationException("plan exceeds 8 nodes");
        }
        List<RewooPlan.Step> steps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            if (!step.isObject()) {
                throw new PlanValidationException("step must be an object");
            }
            StrictJson.fields(step, "id", "purpose", "tool", "arguments");
            String tool = StrictJson.text(step, "tool");
            steps.add(new RewooPlan.Step(StrictJson.text(step, "id"), StrictJson.text(step, "purpose"),
                tools.decode(tool, StrictJson.write(step.get("arguments")))));
        }
        return new RewooPlan(1, steps, StrictJson.strings(root, "answerEvidence"));
    }
}
