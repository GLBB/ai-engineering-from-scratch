package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.AgentRunner;
import dev.learning.agent.core.CompletionCheck;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.planning.AnswerChecks;
import dev.learning.agent.core.planning.ConversationMemory;
import dev.learning.agent.core.planning.ModelJson;
import dev.learning.agent.core.planning.Prompts;
import dev.learning.agent.core.planning.TaskInput;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolSpec;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.DefaultToolExecutionService;
import dev.learning.agent.tool.ToolInput;
import dev.learning.agent.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class RewooRunner implements AgentRunner {
    private final ToolRegistry tools;
    private final ConversationMemory memory = new ConversationMemory();
    public RewooRunner(ToolRegistry tools) { this.tools = tools; }

    public RunResult run(ExecutionRequest request, RunContext context) throws IOException, InterruptedException {
        RunContexts.start(request, context);
        TaskInput task = memory.snapshot(request.prompt());
        Set<String> names = tools.definitions().stream().map(t -> t.function().name()).collect(Collectors.toSet());
        ModelJson.phase(context, "PLAN", "running", null, 0);
        var validated = ModelJson.request(Prompts.read("rewoo-planner"), new PlannerInput(task, tools.definitions()),
                request.settings(), new RunScope("PLAN", null, 0), context,
                text -> RewooValidator.validate(RewooPlanParser.parse(text), names));
        context.emit("plan", new ModelJson.PlanDisplay(AgentMode.REWOO, 0, validated.plan()));
        for (RewooPlan.Step step : validated.order()) {
            context.control().check();
            RunScope scope = new RunScope("WORK", step.id(), 0);
            ModelJson.phase(context, "WORK", "running", step.id(), 0);
            try {
                ToolInput bound = EvidenceBinder.bind(step.arguments(), context.evidence());
                context.tools().invoke(bound, step.id(), names, scope, context);
            } catch (IOException e) {
                boolean dependencyFailed = validated.dependencies().get(step.id()).stream()
                        .anyMatch(id -> !AnswerChecks.usable(context.evidence(), id));
                if (dependencyFailed) DefaultToolExecutionService.skipped(step.tool(), StrictJson.write(step.arguments()),
                        step.id(), scope, context, e.getMessage());
                else DefaultToolExecutionService.rejected(step.tool(), StrictJson.write(step.arguments()),
                        step.id(), scope, context, e.getMessage());
            }
        }
        ModelJson.phase(context, "SOLVE", "running", null, 0);
        var answer = ModelJson.call(Prompts.read("rewoo-solver"),
                StrictJson.write(new SolverInput(task, validated.plan(), context.evidence().snapshot())),
                request.settings(), new RunScope("SOLVE", null, 0), context, true);
        AnswerChecks.citations(answer.content(), validated.plan().answerEvidence(), context.evidence());
        ExecutionStatus status = AnswerChecks.status(validated.plan().answerEvidence(), context.evidence());
        if (status == ExecutionStatus.COMPLETE || status == ExecutionStatus.PARTIAL) memory.commit(request.prompt(), answer.content());
        return new RunResult(status, CompletionCheck.UNVERIFIED, answer.content(),
                status == ExecutionStatus.FAILED ? "MISSING_EVIDENCE" : null, context.budget().snapshot());
    }

    record PlannerInput(TaskInput task, List<ToolSpec> tools) {}
    record SolverInput(TaskInput task, RewooPlan plan, List<Evidence> evidence) {}
}
