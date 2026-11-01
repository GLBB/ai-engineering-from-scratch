package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.EvidenceStore;
import dev.learning.agent.core.runtime.InMemoryEvidenceStore;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.SearchInput;
import dev.learning.agent.tool.ToolRegistry;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RewooRunnerTest {
    private static final String CHAIN = """
            {"version":1,"steps":[
              {"id":"E2","purpose":"divide","tool":"calculator","arguments":{"expression":"#E1/3"}},
              {"id":"E1","purpose":"sum","tool":"calculator","arguments":{"expression":"120+18"}}
            ],"answerEvidence":["E2"]}
            """;

    @Test void executesDependencyOrderWithOnlyPlannerAndSolverModelCalls() throws Exception {
        Outcome outcome = run(CHAIN, "结果是 46 [E2]。");
        assertEquals(ExecutionStatus.COMPLETE, outcome.result().status());
        assertEquals(List.of("E1", "E2"), outcome.context().evidence().snapshot().stream().map(Evidence::id).toList());
        assertEquals("46", outcome.context().evidence().requireUsable("E2").result().content());
        assertEquals(2, outcome.result().counters().modelCalls());
        assertTrue(outcome.requests().stream().allMatch(r -> r.tools().isEmpty()));
        assertFalse(outcome.requests().get(1).messages().stream().anyMatch(m -> "tool".equals(m.role())));
    }

    @Test void invalidPlansFailBeforeAnyToolRuns() throws Exception {
        String invalid = CHAIN.replace("#E1/3", "#E2/3");
        assertThrows(java.io.IOException.class, () -> run(invalid, invalid));
        assertThrows(java.io.IOException.class, () -> RewooValidator.validate(
                RewooPlanParser.parse(CHAIN.replace("\"E1\",\"purpose\"", "\"E2\",\"purpose\"")), Set.of("calculator")));
    }

    @Test void rejectsMissingReferencesUnknownToolsAndWrongScalarTypes() {
        assertThrows(java.io.IOException.class, () -> RewooValidator.validate(
                RewooPlanParser.parse(CHAIN.replace("#E1/3", "#E9/3")), Set.of("calculator")));
        assertThrows(java.io.IOException.class, () -> RewooPlanParser.parse(CHAIN.replace("\"calculator\"", "\"invented\"")));
        assertThrows(java.io.IOException.class, () -> RewooPlanParser.parse(CHAIN.replace("\"120+18\"", "123")));
        assertThrows(java.io.IOException.class, () -> StrictJson.object("{\"version\":1,\"version\":1}"));
    }

    @Test void failureSkipsDependentsButIndependentBranchStillRuns() throws Exception {
        String plan = """
                {"version":1,"steps":[
                {"id":"E1","purpose":"bad","tool":"calculator","arguments":{"expression":"1/0"}},
                {"id":"E2","purpose":"dependent","tool":"calculator","arguments":{"expression":"#E1+1"}},
                {"id":"E3","purpose":"independent","tool":"calculator","arguments":{"expression":"3+4"}}
                ],"answerEvidence":["E2","E3"]}
                """;
        Outcome outcome = run(plan, "第一项无法完成，独立结果为 7 [E3]。");
        assertEquals(ExecutionStatus.PARTIAL, outcome.result().status());
        assertEquals(ToolStatus.ERROR, outcome.context().evidence().snapshot().get(0).result().status());
        assertEquals(ToolStatus.SKIPPED, outcome.context().evidence().snapshot().get(1).result().status());
        assertEquals("7", outcome.context().evidence().requireUsable("E3").result().content());
    }

    @Test void bindingDoesNotRecursivelyInterpretEvidenceAndHandlesE1VsE10() throws Exception {
        EvidenceStore store = new InMemoryEvidenceStore();
        store.put(new Evidence("E1", null, "search", "{}", new ToolResult(ToolStatus.SUCCESS, "#E9", null, true, true, false), 0));
        store.put(new Evidence("E10", null, "search", "{}", new ToolResult(ToolStatus.SUCCESS, "ten", null, true, true, false), 0));
        assertEquals("#E9 and ten", ((SearchInput) EvidenceBinder.bind(new SearchInput("#E1 and #E10"), store)).query());
    }

    @Test void formatRepairIsBoundedAndCanRecoverBeforeExecution() throws Exception {
        Outcome outcome = run("not json", CHAIN, "46 [E2]");
        assertEquals(3, outcome.result().counters().modelCalls());
        assertEquals(2, outcome.result().counters().toolsExecuted());
        assertThrows(java.io.IOException.class, () -> run("not json", "still invalid"));
    }

    @Test void solverCannotCiteMissingEvidence() {
        assertThrows(java.io.IOException.class, () -> run(CHAIN, "99 [E9]"));
    }

    @Test void emptyPlanSupportsOrdinaryConversation() throws Exception {
        Outcome outcome = run("{\"version\":1,\"steps\":[],\"answerEvidence\":[]}", "你好。");
        assertEquals(ExecutionStatus.COMPLETE, outcome.result().status());
        assertEquals(0, outcome.result().counters().toolAttempts());
    }

    private Outcome run(String... outputs) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        List<ModelRequest> requests = new ArrayList<>();
        Deque<String> answers = new ArrayDeque<>(List.of(outputs));
        ChatModel model = (request, text, control) -> {
            requests.add(request);
            String value = answers.removeFirst();
            if (request.use() == ModelUse.FINALIZER) text.accept(value);
            return new AssistantTurn(value, List.of(), "stop");
        };
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.REWOO, model, tools, ignored -> {},
                (kind, content, scope, control) -> false);
        try {
            return new Outcome(new RewooRunner(tools).run(new ExecutionRequest(context.requestId(), "计算并解释", ModelSettings.defaults()), context), context, requests);
        } finally { context.control().close(); }
    }
    record Outcome(RunResult result, RunContext context, List<ModelRequest> requests) {}
}
