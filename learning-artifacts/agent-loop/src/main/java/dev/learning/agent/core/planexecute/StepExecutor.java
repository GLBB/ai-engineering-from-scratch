package dev.learning.agent.core.planexecute;

import dev.learning.agent.core.planning.Prompts;
import dev.learning.agent.core.planning.TaskInput;
import dev.learning.agent.core.react.EngineRequest;
import dev.learning.agent.core.react.EngineResult;
import dev.learning.agent.core.react.EngineStop;
import dev.learning.agent.core.react.ReActEngine;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.support.json.StrictJson;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Each goal gets a fresh, bounded transcript over the shared ReAct engine. */
public final class StepExecutor {
    private final ReActEngine engine;
    public StepExecutor(ReActEngine engine) { this.engine = engine; }
    public StepResult execute(TaskInput task, GoalStep step, ExecutionLedger ledger,
                              ModelSettings settings, RunContext context) throws IOException, InterruptedException {
        var input = new Input(task, step, ledger.attempts(), context.evidence().snapshot());
        var transcript = List.of(ChatMessage.system(Prompts.read("step-executor")), ChatMessage.user(StrictJson.write(input)));
        EngineResult result = engine.execute(new EngineRequest(transcript, settings, Set.copyOf(step.allowedTools()),
                3, 2, false, new RunScope("EXECUTE", step.id(), ledger.current().revision())), context);
        if (result.stop() == EngineStop.NEEDS_INPUT) return incomplete(step, "user clarification required");
        if (result.stop() != EngineStop.FINAL_RESPONSE) return incomplete(step, "local execution budget exhausted");
        try {
            var node = StrictJson.object(result.finalTurn().content());
            StrictJson.fields(node, "status", "summary", "evidenceIds");
            String status = StrictJson.text(node, "status");
            String summary = StrictJson.text(node, "summary");
            var ids = StrictJson.strings(node, "evidenceIds");
            for (String id : ids) context.evidence().requireUsable(id);
            if (!Set.of("COMPLETE", "INCOMPLETE", "FAILED", "DENIED", "CANCELLED").contains(status))
                throw new IOException("invalid step status");
            if (status.equals("COMPLETE") && ids.size() < step.minEvidence()) throw new IOException("insufficient evidence");
            return new StepResult(step.id(), StepResult.Status.valueOf(status), summary, ids);
        } catch (IOException | IllegalArgumentException e) { return incomplete(step, "invalid step answer: " + e.getMessage()); }
    }
    private static StepResult incomplete(GoalStep step, String reason) {
        return new StepResult(step.id(), StepResult.Status.INCOMPLETE, reason, List.of());
    }
    record Input(TaskInput task, GoalStep step, List<StepResult> completedSteps, List<Evidence> evidence) {}
}
