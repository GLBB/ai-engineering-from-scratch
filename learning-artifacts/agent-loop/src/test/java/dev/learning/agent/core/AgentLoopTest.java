package dev.learning.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    @Test
    void finalAnswerIsIncludedInNextUserRequest() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            requests.add(messages);
            return new AssistantTurn("answer", List.of(), "stop");
        };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 1, ignored -> {}, ignored -> {});

        loop.run("first", ModelSettings.defaults());
        loop.run("second", ModelSettings.defaults());

        assertEquals(List.of("system", "user"), roles(requests.get(0)));
        assertEquals(List.of("system", "user", "assistant", "user"), roles(requests.get(1)));
        assertEquals("answer", requests.get(1).get(2).content());
        assertEquals("second", requests.get(1).get(3).content());
    }

    @Test
    void multipleToolsPreserveOrderAndTraceIdsAcrossModelTurns() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> toolNotifications = new ArrayList<>();
        List<TraceEvent> trace = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            requests.add(messages);
            return switch (requests.size()) {
                case 1 -> new AssistantTurn("", List.of(call("a", "1+2"), call("b", "4*5")), "tool_calls");
                case 2 -> new AssistantTurn("", List.of(call("c", "6-1")), "tool_calls");
                default -> new AssistantTurn("done", List.of(), "stop");
            };
        };
        AgentLoop loop = new AgentLoop(model, tools, 3, ignored -> {}, toolNotifications::add, trace::add);

        loop.run("calculate", ModelSettings.defaults());

        assertEquals(List.of("system", "user", "assistant", "tool", "tool"), roles(requests.get(1)));
        List<ChatMessage> results = requests.get(2).stream()
                .filter(message -> "tool".equals(message.role())).toList();
        assertEquals(List.of("a", "b", "c"), results.stream().map(ChatMessage::toolCallId).toList());
        assertEquals(List.of("3", "20", "5"), results.stream().map(ChatMessage::content).toList());
        assertEquals(List.of("tool: calculator", "tool: calculator", "tool: calculator"), toolNotifications);
        List<TraceEvent> started = trace.stream()
                .filter(event -> "tool".equals(event.kind()) && "running".equals(event.status())).toList();
        List<TraceEvent> finished = trace.stream()
                .filter(event -> "tool".equals(event.kind()) && "complete".equals(event.status())).toList();
        String requestId = trace.get(0).id().substring("user-".length());
        assertEquals(List.of("tool-" + requestId + "-1", "tool-" + requestId + "-2",
                "tool-" + requestId + "-3"), started.stream().map(TraceEvent::id).toList());
        assertEquals(started.stream().map(TraceEvent::id).toList(), finished.stream().map(TraceEvent::id).toList());
        assertEquals(List.of(1, 1, 2), started.stream().map(TraceEvent::round).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedFinishReasonDoesNotRecordAnswerOrDispatchTools(boolean withTools) throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> toolNotifications = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            requests.add(messages);
            if (requests.size() == 1) {
                return new AssistantTurn("partial", withTools ? List.of(call("a", "1+2")) : List.of(), "length");
            }
            return new AssistantTurn("done", List.of(), "stop");
        };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 2, ignored -> {}, toolNotifications::add);

        IOException error = assertThrows(IOException.class, () -> loop.run("first", ModelSettings.defaults()));
        assertEquals(withTools ? "DeepSeek returned tools with unexpected finish reason: length"
                : "DeepSeek stopped unexpectedly: length", error.getMessage());
        loop.run("retry", ModelSettings.defaults());

        assertTrue(toolNotifications.isEmpty());
        assertEquals(List.of("system", "user", "user"), roles(requests.get(1)));
    }

    @Test
    void turnLimitStillRecordsLastToolResult() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            requests.add(messages);
            return new AssistantTurn("", List.of(new ToolCall("missing", "function",
                    new ToolCall.FunctionCall("unknown", "{}"))), "tool_calls");
        };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 2, ignored -> {}, ignored -> {});

        IOException error = assertThrows(IOException.class, () -> loop.run("first", ModelSettings.defaults()));
        assertEquals("agent stopped after 2 model turns", error.getMessage());
        assertEquals(2, requests.size());
        assertThrows(IOException.class, () -> loop.run("next", ModelSettings.defaults()));

        List<ChatMessage> nextRequest = requests.get(2);
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant", "tool", "user"),
                roles(nextRequest));
        assertEquals("error: unknown tool unknown", nextRequest.get(5).content());
    }

    @Test
    void interruptedModelRequestIsTracedAndRethrown() {
        InterruptedException failure = new InterruptedException("cancelled");
        List<TraceEvent> trace = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> { throw failure; };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 2, ignored -> {}, ignored -> {}, trace::add);

        assertSame(failure, assertThrows(InterruptedException.class, () -> loop.run("hello", ModelSettings.defaults())));
        assertEquals(List.of("complete", "complete", "running", "error"),
                trace.stream().map(TraceEvent::status).toList());
        assertEquals("cancelled", trace.get(3).output());
        assertEquals(trace.get(2).id(), trace.get(3).id());
    }

    @Test
    void runtimeFailureWithoutMessageUsesExceptionNameInTrace() {
        IllegalStateException failure = new IllegalStateException();
        List<TraceEvent> trace = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> { throw failure; };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 2, ignored -> {}, ignored -> {}, trace::add);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> loop.run("hello", ModelSettings.defaults())));
        assertEquals("error", trace.get(3).status());
        assertEquals("IllegalStateException", trace.get(3).output());
    }

    @Test
    void invalidInputDoesNotChangeHistoryOrEmitTrace() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<TraceEvent> trace = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            requests.add(messages);
            return new AssistantTurn("done", List.of(), "stop");
        };
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), 1, ignored -> {}, ignored -> {}, trace::add);

        assertThrows(IllegalArgumentException.class, () -> loop.run(null, ModelSettings.defaults()));
        assertThrows(IllegalArgumentException.class, () -> loop.run(" ", ModelSettings.defaults()));
        assertThrows(IllegalArgumentException.class, () -> loop.run("hello", null));
        assertTrue(requests.isEmpty());
        assertTrue(trace.isEmpty());
        loop.run("valid", ModelSettings.defaults());
        assertEquals(List.of("system", "user"), roles(requests.get(0)));
    }

    private static ToolCall call(String id, String expression) {
        return new ToolCall(id, "function", new ToolCall.FunctionCall("calculator",
                "{\"expression\":\"" + expression + "\"}"));
    }

    private static List<String> roles(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::role).toList();
    }
}
