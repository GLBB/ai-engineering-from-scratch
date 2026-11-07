package dev.learning.agent.core.planexecute;

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
import dev.learning.agent.core.react.ReActEngine;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolSpec;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.ToolRegistry;
import dev.learning.agent.tool.ToolStatus;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class PlanExecuteRunner implements AgentRunner {
    private final ToolRegistry tools;
    private final StepExecutor executor;
    private final ConversationMemory memory;
    public PlanExecuteRunner(ToolRegistry tools, ReActEngine engine) { this(tools, engine, List.of()); }
    public PlanExecuteRunner(ToolRegistry tools, ReActEngine engine, List<TaskInput.ConversationTurn> history) {
        this.tools = tools;
        executor = new StepExecutor(engine);
        memory = new ConversationMemory(history);
    }
    public RunResult run(ExecutionRequest request, RunContext context) throws IOException, InterruptedException {
        RunContexts.start(request, context);
        TaskInput task = memory.snapshot(request.prompt());
        Set<String> names = tools.definitions().stream().map(t -> t.function().name()).collect(Collectors.toSet());
        ModelJson.phase(context, "PLAN", "running", null, 0);
        RunScope planScope = new RunScope("PLAN", null, 0);
        GoalPlan initial = context.telemetry().observe(planScope, () -> {
            GoalPlan value = ModelJson.request(Prompts.read("goal-planner"), new PlannerInput(task, tools.definitions()),
                    request.settings(), planScope, context, text -> GoalPlans.validate(GoalPlans.parse(text), names));
            context.emit("plan", new ModelJson.PlanDisplay(AgentMode.PLAN_EXECUTE, value.revision(), value));
            return value;
        });
        ExecutionLedger ledger = new ExecutionLedger(initial);
        String termination = "active plan completed";
        List<String> answerEvidence = List.of();
        boolean explicitPartial = false;
        while (!ledger.complete()) {
            GoalStep step = ledger.next();
            if (step == null || !context.budget().canStartStep()) { termination = "no executable step or remaining budget"; break; }
            context.control().check();
            context.budget().claimStep();
            ModelJson.phase(context, "EXECUTE", "running", step.id(), ledger.current().revision());
            StepResult result = context.telemetry().observe(new RunScope("EXECUTE", step.id(), ledger.current().revision()), () -> {
                StepResult value = executor.execute(task, step, ledger, request.settings(), context);
                context.emit("step_result", value);
                return value;
            });
            ledger.record(result);
            if (context.needsInput()) { termination = "user clarification required; do not revise or guess"; break; }
            ModelJson.phase(context, "REPLAN", "running", step.id(), ledger.current().revision());
            ReviewInput input = new ReviewInput(task, ledger.current(), ledger.attempts(), context.evidence().snapshot());
            RunScope reviewScope = new RunScope("REPLAN", step.id(), ledger.current().revision());
            ReplanDecision decision = context.telemetry().observe(reviewScope, () -> {
                ReplanDecision value = ModelJson.request(Prompts.read("replanner"), input, request.settings(), reviewScope, context, text -> {
                        ReplanDecision parsed = GoalPlans.decision(text);
                        if (parsed instanceof ReplanDecision.ReviseDecision revision) ledger.revision(revision.remainingSteps(), names);
                        if (parsed instanceof ReplanDecision.FinishDecision finish) {
                            for (String id : finish.answerEvidence()) context.evidence().requireUsable(id);
                            if (finish.status().equals("COMPLETE") && !ledger.complete()) throw new IOException("required steps remain incomplete");
                        }
                        if (parsed instanceof ReplanDecision.ContinueDecision && ledger.next() == null)
                            throw new IOException("CONTINUE requires a new executable step");
                        return parsed;
                });
                context.emit("decision", value);
                return value;
            });
            termination = decision.reason();
            if (decision instanceof ReplanDecision.ReviseDecision revision) {
                GoalPlan next = ledger.revision(revision.remainingSteps(), names);
                if (context.budget().snapshot().planRevisions() >= 2) { termination = "plan revision budget exhausted"; break; }
                context.budget().claimRevision(); ledger.commit(next); display(context, ledger);
            } else if (decision instanceof ReplanDecision.FinishDecision finish) {
                answerEvidence = finish.answerEvidence(); explicitPartial = finish.status().equals("PARTIAL"); break;
            } else if (decision instanceof ReplanDecision.StopDecision) { explicitPartial = true; break; }
        }
        if (answerEvidence.isEmpty()) answerEvidence = ledger.attempts().stream()
                .filter(r -> r.status() == StepResult.Status.COMPLETE).flatMap(r -> r.evidenceIds().stream()).distinct().toList();
        context.control().check();
        ModelJson.phase(context, "FINALIZE", "running", null, ledger.current().revision());
        var finalInput = new FinalInput(task, ledger.versions(), ledger.attempts(), ledger.superseded(), context.evidence().snapshot(), termination, answerEvidence);
        RunScope finalScope = new RunScope("FINALIZE", null, ledger.current().revision());
        String answer = context.telemetry().observe(finalScope, () -> {
            String value = ModelJson.call(Prompts.read("plan-finalizer"), StrictJson.write(finalInput),
                    request.settings(), finalScope, context, true).content();
            AnswerChecks.citations(value, finalInput.answerEvidence(), context.evidence());
            return value;
        });
        boolean hasEvidence = context.evidence().snapshot().stream().anyMatch(e -> e.result().status() == ToolStatus.SUCCESS
                && e.result().hasValue() && !e.result().truncated());
        ExecutionStatus status = context.needsInput() ? ExecutionStatus.NEEDS_INPUT : ledger.complete() && !explicitPartial ? ExecutionStatus.COMPLETE
                : hasEvidence ? ExecutionStatus.PARTIAL : ExecutionStatus.FAILED;
        if (status != ExecutionStatus.FAILED)
            memory.commit(new TaskInput.ConversationTurn(request.prompt(), answer, status, List.of()));
        return new RunResult(status, CompletionCheck.UNVERIFIED, answer,
                status == ExecutionStatus.COMPLETE ? null : status == ExecutionStatus.NEEDS_INPUT ? "NEEDS_INPUT" : "INCOMPLETE_PLAN", context.budget().snapshot());
    }
    private static void display(RunContext context, ExecutionLedger ledger) {
        context.emit("plan", new ModelJson.PlanDisplay(AgentMode.PLAN_EXECUTE, ledger.current().revision(), ledger.current()));
    }
    record PlannerInput(TaskInput task, List<ToolSpec> tools) {}
    record ReviewInput(TaskInput task, GoalPlan plan, List<StepResult> attempts, List<Evidence> evidence) {}
    record FinalInput(TaskInput task, List<GoalPlan> versions, List<StepResult> attempts, Set<String> superseded,
                      List<Evidence> evidence, String termination, List<String> answerEvidence) {}
}
