package dev.learning.agent.core.planexecute;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.react.DefaultReActEngine;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.model.ToolCall;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.ToolRegistry;
import dev.learning.agent.tool.ToolStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteRunnerTest {
    private static String step(String id, String goal, int minimum) {
        return "{\"id\":\"" + id + "\",\"goal\":\"" + goal
            + "\",\"successCriteria\":\"numeric evidence\",\"minEvidence\":" + minimum
            + ",\"allowedTools\":[\"calculator\"],\"dependsOn\":[]}";
    }

    private static String plan(String... steps) {
        return "{\"version\":1,\"revision\":0,\"steps\":[" + String.join(",", steps) + "]}";
    }

    private static String complete(String evidence) {
        return "{\"status\":\"COMPLETE\",\"summary\":\"done\",\"evidenceIds\":[\"" + evidence + "\"]}";
    }

    private static AssistantTurn text(String content) {
        return new AssistantTurn(content, List.of(), "stop");
    }

    private static AssistantTurn calculator(String expression) {
        return new AssistantTurn("", List.of(new ToolCall("native", "function",
            new ToolCall.FunctionCall("calculator", "{\"expression\":\"" + expression + "\"}"))), "tool_calls");
    }

    @Test
    void executesStepThroughSharedEngineThenReviewsAndFinalizes() throws Exception {
        Outcome result = run(text(plan(step("S1", "sum", 1))), calculator("120+18"), text(complete("E1")),
            text("{\"decision\":\"FINISH\",\"reason\":\"done\",\"status\":\"COMPLETE\",\"answerEvidence\":[\"E1\"]}"),
            text("138 [E1]"));
        assertEquals(ExecutionStatus.COMPLETE, result.result.status());
        assertEquals(5, result.result.counters().modelCalls());
        assertEquals(1, result.result.counters().stepAttempts());
        assertTrue(result.requests.get(0).tools().isEmpty());
        assertEquals(1, result.requests.get(1).tools().size());
        assertTrue(result.requests.get(3).tools().isEmpty());
        assertTrue(result.requests.get(4).tools().isEmpty());
    }

    @Test
    void revisionRetainsSuccessfulEvidenceAndFailedAttempts() throws Exception {
        String initial = plan(step("S1", "sum", 1), step("S2", "divide", 1));
        String revision = "{\"decision\":\"REVISE\",\"reason\":\"different approach\",\"remainingSteps\":["
            + step("S3", "recover with multiplication", 1) + "]}";
        Outcome result = run(text(initial), calculator("120+18"), text(complete("E1")),
            text("{\"decision\":\"CONTINUE\",\"reason\":\"divide next\"}"),
            text("{\"status\":\"INCOMPLETE\",\"summary\":\"need another approach\",\"evidenceIds\":[]}"),
            text(revision), calculator("138/3"), text(complete("E2")),
            text("{\"decision\":\"FINISH\",\"reason\":\"done\",\"status\":\"COMPLET"
                + "E\",\"answerEvidence\":[\"E1\",\"E2\"]}"),
            text("和为 138 [E1]，结果 46 [E2]。"));
        assertEquals(ExecutionStatus.COMPLETE, result.result.status());
        assertEquals(1, result.result.counters().planRevisions());
        assertEquals(3, result.result.counters().stepAttempts());
        String finalData = result.requests.get(result.requests.size() - 1).messages().get(1).content();
        assertTrue(finalData.contains("INCOMPLETE"));
        assertTrue(finalData.contains("S2"));
        assertEquals(2, result.result.counters().toolsExecuted());
    }

    @Test
    void rejectsForgedCompletionAndUsesOnlyOneRepair() {
        String falseFinish =
            "{\"decision\":\"FINISH\",\"reason\":\"done\",\"status\":\"COMPLETE\",\"answerEvidence\":[]}";
        assertThrows(java.io.IOException.class,
            () -> run(text(plan(step("S1", "sum", 1))), text(complete("E999")), text(falseFinish), text(falseFinish)));
    }

    @Test
    void localToolBudgetStopsUnboundedExecutorAndKeepsPartialEvidence() throws Exception {
        Outcome result = run(text(plan(step("S1", "loop", 1))), calculator("1+1"), calculator("2+2"), calculator("3+3"),
            text("{\"decision\":\"STOP\",\"reason\":\"local budget exhausted\"}"),
            text("Only partial evidence is available [E1]."));
        assertEquals(ExecutionStatus.PARTIAL, result.result.status());
        assertEquals(2, result.result.counters().toolAttempts());
        assertEquals(ToolStatus.SKIPPED, result.context.evidence().snapshot().get(2).result().status());
    }

    @Test
    void validationRejectsCyclesHistoryReuseAndRenamedNoProgressPlan() throws Exception {
        GoalPlan initial = GoalPlans.parse(plan(step("S1", "sum", 1)));
        ExecutionLedger ledger = new ExecutionLedger(initial);
        assertThrows(java.io.IOException.class, () -> ledger.revision(initial.steps(), Set.of("calculator")));
        GoalStep renamed = new GoalStep("S2", "sum", "same", 1, List.of("calculator"), List.of());
        assertThrows(java.io.IOException.class, () -> ledger.revision(List.of(renamed), Set.of("calculator")));
        GoalStep cyclic = new GoalStep("S1", "x", "x", 0, List.of(), List.of("S1"));
        assertThrows(java.io.IOException.class,
            () -> GoalPlans.validate(new GoalPlan(1, 0, List.of(cyclic)), Set.of()));
        assertThrows(java.io.IOException.class,
            () -> GoalPlans.parse(plan(step("S1", "sum", 1)).replace("\"minEvidence\":1", "\"minEvidence\":\"1\"")));
    }

    @Test
    void emptyPlanNeedsOnlyPlannerAndFinalizer() throws Exception {
        Outcome result = run(text(plan()), text("你好"));
        assertEquals(ExecutionStatus.COMPLETE, result.result.status());
        assertEquals(2, result.result.counters().modelCalls());
    }

    private Outcome run(AssistantTurn... outputs) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        Deque<AssistantTurn> answers = new ArrayDeque<>(List.of(outputs));
        List<ModelRequest> requests = new ArrayList<>();
        ChatModel model = (request, onText, control) -> {
            requests.add(request);
            AssistantTurn answer = answers.removeFirst();
            if (request.use() == ModelUse.FINALIZER) {
                onText.accept(answer.content());
            }
            return answer;
        };
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.PLAN_EXECUTE, model, tools, e -> {
        }, (k, c, s, r) -> false);
        try {
            return new Outcome(
                new PlanExecuteRunner(tools, new DefaultReActEngine(tools))
                    .run(new ExecutionRequest(context.requestId(), "calculate", ModelSettings.defaults()), context),
                context, requests);
        } finally {
            context.control().close();
        }
    }

    record Outcome(RunResult result, RunContext context, List<ModelRequest> requests) {
    }
}
