package dev.learning.agent.core.planexecute;

import dev.learning.agent.core.*;
import dev.learning.agent.core.planning.*;
import dev.learning.agent.deepseek.model.ToolSpec;
import dev.learning.agent.tool.ToolRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class PlanExecuteRunner implements AgentRunner {
    private final ToolRegistry tools;
    private final StepExecutor executor;
    private final ConversationMemory memory = new ConversationMemory();
    public PlanExecuteRunner(ToolRegistry tools, ReActEngine engine) { this.tools = tools; executor = new StepExecutor(engine); }
    public RunResult run(ExecutionRequest request, RunContext context) throws IOException, InterruptedException {
        RunContexts.start(request, context);
        TaskInput task = memory.snapshot(request.prompt());
        Set<String> names = tools.definitions().stream().map(t -> t.function().name()).collect(Collectors.toSet());
        ModelJson.phase(context, "PLAN", "running", null, 0);
        GoalPlan initial = ModelJson.request(Prompts.read("goal-planner"), new PlannerInput(task, tools.definitions()),
                request.settings(), new RunScope("PLAN", null, 0), context,
                text -> GoalPlans.validate(GoalPlans.parse(text), names));
        ExecutionLedger ledger = new ExecutionLedger(initial);
        display(context, ledger);
        String termination = "active plan completed";
        List<String> answerEvidence = List.of();
        boolean explicitPartial = false;
        while (!ledger.complete()) {
            GoalStep step = ledger.next();
            if (step == null || !context.budget().canStartStep()) { termination = "no executable step or remaining budget"; break; }
            context.control().check();
            context.budget().claimStep();
            ModelJson.phase(context, "EXECUTE", "running", step.id(), ledger.current().revision());
            StepResult result = executor.execute(task, step, ledger, request.settings(), context);
            ledger.record(result);
            context.emit("step_result", result);
            ModelJson.phase(context, "REPLAN", "running", step.id(), ledger.current().revision());
            ReviewInput input = new ReviewInput(task, ledger.current(), ledger.attempts(), context.evidence().snapshot());
            ReplanDecision decision = ModelJson.request(Prompts.read("replanner"), input, request.settings(),
                    new RunScope("REPLAN", step.id(), ledger.current().revision()), context, text -> {
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
            context.emit("decision", decision);
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
        String answer = ModelJson.call(Prompts.read("plan-finalizer"), StrictJson.write(new FinalInput(task,
                ledger.versions(), ledger.attempts(), ledger.superseded(), context.evidence().snapshot(), termination, answerEvidence)),
                request.settings(), new RunScope("FINALIZE", null, ledger.current().revision()), context, true).content();
        AnswerChecks.citations(answer, answerEvidence, context.evidence());
        boolean hasEvidence = context.evidence().snapshot().stream().anyMatch(e -> e.result().status() == ToolStatus.SUCCESS
                && e.result().hasValue() && !e.result().truncated());
        ExecutionStatus status = ledger.complete() && !explicitPartial ? ExecutionStatus.COMPLETE
                : hasEvidence ? ExecutionStatus.PARTIAL : ExecutionStatus.FAILED;
        if (status != ExecutionStatus.FAILED) memory.commit(request.prompt(), answer);
        return new RunResult(status, CompletionCheck.UNVERIFIED, answer,
                status == ExecutionStatus.COMPLETE ? null : "INCOMPLETE_PLAN", context.budget().snapshot());
    }
    private static void display(RunContext context, ExecutionLedger ledger) {
        context.emit("plan", new ModelJson.PlanDisplay(AgentMode.PLAN_EXECUTE, ledger.current().revision(), ledger.current()));
    }
    record PlannerInput(TaskInput task, List<ToolSpec> tools) {}
    record ReviewInput(TaskInput task, GoalPlan plan, List<StepResult> attempts, List<Evidence> evidence) {}
    record FinalInput(TaskInput task, List<GoalPlan> versions, List<StepResult> attempts, Set<String> superseded,
                      List<Evidence> evidence, String termination, List<String> answerEvidence) {}
}
