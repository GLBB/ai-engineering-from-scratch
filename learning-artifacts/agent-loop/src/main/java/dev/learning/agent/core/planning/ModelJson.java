package dev.learning.agent.core.planning;

import dev.learning.agent.core.*;
import dev.learning.agent.deepseek.model.*;
import java.io.IOException;
import java.util.List;

/** A single shared repair allowance, separate from tool execution and replanning. */
public final class ModelJson {
    private ModelJson() {}
    @FunctionalInterface public interface Parser<T> { T parse(String text) throws IOException; }

    public static <T> T request(String prompt, Object payload, ModelSettings settings,
                               RunScope scope, RunContext context, Parser<T> parser)
            throws IOException, InterruptedException {
        String original = StrictJson.write(payload);
        AssistantTurn answer = call(prompt, original, settings, scope, context, false);
        try { return parser.parse(answer.content()); }
        catch (IOException | IllegalArgumentException e) {
            if (!context.budget().claimFormatRepair()) throw new PlanValidationException(e.getMessage());
            context.emit("phase", new Phase("REPAIR", "running", scope.stepId(), scope.revision()));
            RepairInput repair = new RepairInput(prompt, original, answer.content(), e.getMessage());
            AssistantTurn fixed = call(Prompts.read("json-repair"), StrictJson.write(repair), settings,
                    new RunScope("REPAIR", scope.stepId(), scope.revision()), context, false);
            return parser.parse(fixed.content());
        }
    }

    public static AssistantTurn call(String prompt, String data, ModelSettings settings,
                                      RunScope scope, RunContext context, boolean finalAnswer)
            throws IOException, InterruptedException {
        var request = new ModelRequest(List.of(ChatMessage.system(prompt), ChatMessage.user(data)), settings,
                List.of(), finalAnswer ? OutputMode.TEXT : OutputMode.JSON_OBJECT, 4096,
                finalAnswer ? ModelUse.FINALIZER : ModelUse.CONTROL);
        AssistantTurn answer = context.model().call(request, scope,
                part -> { if (finalAnswer) context.emit("text", part); }, context);
        if (!answer.calls().isEmpty() || !"stop".equals(answer.finishReason()) || (finalAnswer && answer.content().isBlank())) {
            throw new IOException("planning/solving response must be nonblank text without tool calls");
        }
        return answer;
    }

    public static void phase(RunContext context, String phase, String status, String step, int revision) {
        context.emit("phase", new Phase(phase, status, step, revision));
    }
    public record Phase(String phase, String status, String stepId, int revision) {}
    public record PlanDisplay(AgentMode mode, int revision, Object plan) {}
    record RepairInput(String contract, String originalInput, String invalidOutput, String error) {}
}
