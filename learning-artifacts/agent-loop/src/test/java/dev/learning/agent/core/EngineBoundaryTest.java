package dev.learning.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.deepseek.model.*;
import dev.learning.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EngineBoundaryTest {
    @Test void localCapReturnsPairedSkippedResultForEveryUnexecutedCall() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        List<ModelRequest> requests = new ArrayList<>();
        ChatModel model = (request, onText, control) -> {
            requests.add(request);
            if (requests.size() == 1) return new AssistantTurn("", List.of(calc("a"), calc("b"), calc("c")), "tool_calls");
            return new AssistantTurn("partial", List.of(), "stop");
        };
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.REACT, model, tools, ignored -> {},
                (kind, content, scope, control) -> false);
        try {
            new DefaultReActEngine(tools).execute(new EngineRequest(List.of(ChatMessage.user("calculate")),
                    ModelSettings.defaults(), Set.of("calculator"), 2, 2, false, new RunScope("TEST", null, 0)), context);
            var results = requests.get(1).messages().stream().filter(m -> "tool".equals(m.role())).toList();
            assertEquals(List.of("a", "b", "c"), results.stream().map(ChatMessage::toolCallId).toList());
            assertEquals("SKIPPED", StrictJson.object(results.get(2).content()).path("status").asText());
            assertEquals(2, context.budget().snapshot().toolsExecuted());
        } finally { context.control().close(); }
    }

    @Test void deniedOutputNeverEntersEvidenceOrNextModelRequest(@TempDir Path directory) throws Exception {
        String secret = "PRIVATE_OUTPUT_7319";
        Files.writeString(directory.resolve("private.txt"), secret);
        ToolRegistry tools = new ToolRegistry();
        tools.register(new BashTool(new ObjectMapper(), directory, ignored -> false, ignored -> false));
        List<ModelRequest> requests = new ArrayList<>();
        ChatModel model = (request, text, control) -> {
            requests.add(request);
            if (requests.size() == 1) return new AssistantTurn("", List.of(new ToolCall("bash-1", "function",
                    new ToolCall.FunctionCall("bash", "{\"command\":\"cat private.txt\"}"))), "tool_calls");
            return new AssistantTurn("output was withheld", List.of(), "stop");
        };
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.REACT, model, tools, ignored -> {},
                (kind, content, scope, control) -> "command".equals(kind));
        try {
            new DefaultReActEngine(tools).execute(new EngineRequest(List.of(ChatMessage.user("read file")),
                    ModelSettings.defaults(), Set.of("bash"), 2, 2, false, new RunScope("TEST", null, 0)), context);
            Evidence evidence = context.evidence().snapshot().get(0);
            assertEquals(ToolStatus.DENIED, evidence.result().status());
            assertTrue(evidence.result().executed());
            assertFalse(StrictJson.write(context.evidence().snapshot()).contains(secret));
            assertFalse(StrictJson.write(requests).contains(secret));
        } finally { context.control().close(); }
    }

    @Test void repeatedBashIsBlockedAndFailedExecutedOperationsAreCounted(@TempDir Path directory) {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new BashTool(new ObjectMapper(), directory, ignored -> false, ignored -> false));
        tools.register(new CalculatorTool(new ObjectMapper()));
        int[] approvals = {0};
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.PLAN_EXECUTE,
                (r,t,c) -> new AssistantTurn("", List.of(), "stop"), tools, ignored -> {},
                (kind, content, scope, control) -> { approvals[0]++; return false; });
        try {
            RunScope scope = new RunScope("EXECUTE", "S1", 0);
            context.tools().invoke(new BashInput("pwd"), "E1", Set.of("bash"), scope, context);
            Evidence duplicate = context.tools().invoke(new BashInput("pwd"), "E2", Set.of("bash"), scope, context);
            assertEquals("DUPLICATE_BASH", duplicate.result().errorCode());
            assertEquals(1, approvals[0]);
            Evidence failed = context.tools().invoke(new CalculatorInput("1/0"), "E3", Set.of("calculator"), scope, context);
            assertEquals(ToolStatus.ERROR, failed.result().status());
            assertTrue(failed.result().executed());
            assertEquals(1, context.budget().snapshot().toolsExecuted());
        } finally { context.control().close(); }
    }

    private static ToolCall calc(String id) {
        return new ToolCall(id, "function", new ToolCall.FunctionCall("calculator", "{\"expression\":\"1+2\"}"));
    }
}
