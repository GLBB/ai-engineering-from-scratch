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
import dev.learning.agent.core.runtime.TraceSpan;
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
    private final ConversationMemory memory;

    public RewooRunner(ToolRegistry tools) {
        this(tools, List.of());
    }

    public RewooRunner(ToolRegistry tools, List<TaskInput.ConversationTurn> history) {
        this.tools = tools;
        memory = new ConversationMemory(history);
    }

    public RunResult run(ExecutionRequest request, RunContext context) throws IOException, InterruptedException {
        RunContexts.start(request, context);
        TaskInput task = memory.snapshot(request.prompt());
        Set<String> names = tools.definitions().stream().map(t -> t.function().name()).collect(Collectors.toSet());
        ModelJson.phase(context, "PLAN", "running", null, 0);
        RunScope planScope = new RunScope("PLAN", null, 0);
        var validated = context.telemetry().observe(planScope, () -> {
            var value = ModelJson.request(Prompts.read("rewoo-planner"), new PlannerInput(task, tools.definitions()),
                request.settings(), planScope, context,
                text -> RewooValidator.validate(RewooPlanParser.parse(text, tools), names));
            context.emit("plan", new ModelJson.PlanDisplay(AgentMode.REWOO, 0, value.plan()));
            return value;
        });
        for (RewooPlan.Step step : validated.order()) {
            context.control().check();
            RunScope scope = new RunScope("WORK", step.id(), 0);
            try (TraceSpan work = context.telemetry().phase(scope)) {
                ModelJson.phase(context, "WORK", "running", step.id(), 0);
                if (context.needsInput()) {
                    DefaultToolExecutionService.skipped(step.tool(), StrictJson.write(step.arguments()), step.id(),
                        scope, context, "awaiting user input");
                    continue;
                }
                try {
                    ToolInput bound = EvidenceBinder.bind(step.arguments(), context.evidence(), tools);
                    context.tools().invoke(bound, step.id(), names, scope, context);
                } catch (IOException e) {
                    boolean dependencyFailed = validated.dependencies().get(step.id()).stream()
                        .anyMatch(id -> !AnswerChecks.usable(context.evidence(), id));
                    if (dependencyFailed) {
                        DefaultToolExecutionService.skipped(step.tool(), StrictJson.write(step.arguments()), step.id(),
                            scope, context, e.getMessage());
                    } else {
                        work.error("BINDING_FAILED");
                        DefaultToolExecutionService.rejected(step.tool(), StrictJson.write(step.arguments()), step.id(),
                            scope, context, e.getMessage());
                    }
                }
            }
        }
        ModelJson.phase(context, "SOLVE", "running", null, 0);
        RunScope solveScope = new RunScope("SOLVE", null, 0);
        var answer = context.telemetry().observe(solveScope, () -> {
            var value = ModelJson.call(Prompts.read("rewoo-solver"),
                StrictJson.write(new SolverInput(task, validated.plan(), context.evidence().snapshot())),
                request.settings(), solveScope, context, true);
            AnswerChecks.citations(value.content(), validated.plan().answerEvidence(), context.evidence());
            return value;
        });
        ExecutionStatus status = AnswerChecks.status(validated.plan().answerEvidence(), context.evidence());
        if (status == ExecutionStatus.COMPLETE || status == ExecutionStatus.PARTIAL
            || status == ExecutionStatus.NEEDS_INPUT) {
            memory.commit(new TaskInput.ConversationTurn(request.prompt(), answer.content(), status, List.of()));
        }
        return new RunResult(status, CompletionCheck.UNVERIFIED, answer.content(), status == ExecutionStatus.NEEDS_INPUT
            ? "NEEDS_INPUT" : status == ExecutionStatus.FAILED ? "MISSING_EVIDENCE" : null,
            context.budget().snapshot());
    }

    record PlannerInput(TaskInput task, List<ToolSpec> tools) {
    }

    record SolverInput(TaskInput task, RewooPlan plan, List<Evidence> evidence) {
    }
}
