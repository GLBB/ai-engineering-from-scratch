package dev.learning.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTraceTest {
    @Test
    void traceShowsModelRequestToolArgumentsResultAndFinalAnswer() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        List<TraceEvent> trace = new ArrayList<>();
        List<String> text = new ArrayList<>();
        ToolCall calculator = new ToolCall("call-1", "function",
                new ToolCall.FunctionCall("calculator", "{\"expression\":\"(120+18)/3\"}"));
        ChatModel model = (messages, definitions, onText, settings) -> {
            if (messages.stream().noneMatch(message -> "tool".equals(message.role()))) {
                return new AssistantTurn("", List.of(calculator), "tool_calls");
            }
            onText.accept("结果是 46");
            return new AssistantTurn("结果是 46", List.of(), "stop");
        };

        AgentLoop loop = new AgentLoop(model, tools, 8, text::add, ignored -> {}, trace::add);
        loop.run("请计算 (120+18)/3", ModelSettings.defaults());

        assertEquals(List.of("结果是 46"), text);
        assertEquals(List.of("user", "settings", "model", "model", "tool", "tool",
                        "model", "model", "assistant"),
                trace.stream().map(TraceEvent::kind).toList());
        assertEquals(trace.get(2).id(), trace.get(3).id());
        assertEquals(trace.get(4).id(), trace.get(5).id());
        assertEquals("{\"expression\":\"(120+18)/3\"}", trace.get(4).input());
        assertEquals("46", trace.get(5).output());
        assertEquals("结果是 46", trace.get(8).output());
        assertTrue(trace.get(5).durationMs() >= 0);
    }

    @Test
    void failedModelRequestIsMarkedInTrace() {
        ToolRegistry tools = new ToolRegistry();
        List<TraceEvent> trace = new ArrayList<>();
        ChatModel model = (messages, definitions, onText, settings) -> {
            throw new IOException("model unavailable");
        };
        AgentLoop loop = new AgentLoop(model, tools, 1, ignored -> {}, ignored -> {}, trace::add);

        assertThrows(IOException.class, () -> loop.run("hello", ModelSettings.defaults()));
        assertEquals("running", trace.get(2).status());
        assertEquals("error", trace.get(3).status());
        assertEquals(trace.get(2).id(), trace.get(3).id());
        assertEquals("model unavailable", trace.get(3).output());
    }

    @Test
    void thinkingWithToolsReplaysReasoningToNextModelRequest() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(new ObjectMapper()));
        List<TraceEvent> trace = new ArrayList<>();
        ToolCall calculator = new ToolCall("call-1", "function",
                new ToolCall.FunctionCall("calculator", "{\"expression\":\"1+2\"}"));
        ChatModel model = (messages, definitions, onText, settings) -> {
            assertEquals("high", settings.reasoningLevel());
            if (messages.stream().noneMatch(message -> "tool".equals(message.role()))) {
                return new AssistantTurn("", List.of(calculator), "tool_calls", "Use calculator.");
            }
            assertEquals("Use calculator.", messages.stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .findFirst().orElseThrow().reasoningContent());
            return new AssistantTurn("3", List.of(), "stop", "The result is three.");
        };
        AgentLoop loop = new AgentLoop(model, tools, 8, ignored -> {}, ignored -> {}, trace::add);

        loop.run("计算 1+2", new ModelSettings("deepseek-flash", "high"));

        assertTrue(trace.stream().anyMatch(event -> "settings".equals(event.kind())
                && event.input().contains("推理等级 high")));
        assertEquals(List.of("Use calculator.", "The result is three."), trace.stream()
                .filter(event -> "reasoning".equals(event.kind()))
                .map(TraceEvent::output).toList());
    }
}
