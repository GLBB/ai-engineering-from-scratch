package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ChatRequest;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.ToolRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline experiments for docs/05-tool-calls-and-transcripts.md.
 * Exercises the project's actual request adapters without sending HTTP requests.
 * Writes inspectable request fixtures under target/tool-transcript-experiment/.
 * Missing results are diagnosed here; the adapters do not validate pairing.
 */
class ToolTranscriptExperimentTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<ToolCall> calls = List.of(
            call("call_a", "1+2"), call("call_b", "4*5"), call("call_c", "6-1"));

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses", "anthropic"})
    void droppingThirdResultLeavesAnUnansweredCall(String protocol) throws Exception {
        List<ChatMessage> history = startHistory();
        ToolRegistry tools = calculator();
        for (ToolCall call : calls.subList(0, 2)) {
            history.add(ChatMessage.tool(call.id(), tools.dispatch(call)));
        }

        Transcript transcript = serialize(protocol, "missing", history);

        assertEquals(List.of("call_a", "call_b", "call_c"), transcript.requestedIds());
        assertEquals(List.of("call_a", "call_b"), transcript.resultIds());
        assertEquals(List.of("call_c"), transcript.unansweredIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses", "anthropic"})
    void executionCapCanReturnAnErrorForTheUnexecutedCall(String protocol) throws Exception {
        List<ChatMessage> history = startHistory();
        ToolRegistry tools = calculator();
        List<String> executed = new ArrayList<>();
        int maxToolCallsPerTurn = 2;
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            String result;
            if (index < maxToolCallsPerTurn) {
                executed.add(call.id());
                result = tools.dispatch(call);
            } else {
                result = "error: not executed; max_tool_calls_per_turn=2";
            }
            history.add(ChatMessage.tool(call.id(), result));
        }

        Transcript transcript = serialize(protocol, "capped", history);

        assertEquals(List.of("call_a", "call_b"), executed);
        assertTrue(transcript.unansweredIds().isEmpty());
        assertEquals(Map.of("call_a", "3", "call_b", "20",
                "call_c", "error: not executed; max_tool_calls_per_turn=2"), transcript.results());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses", "anthropic"})
    void reverseCompletionOrderPreservesTheCallResultAssociation(String protocol) throws Exception {
        List<ChatMessage> history = startHistory();
        ToolRegistry tools = calculator();
        for (int index = calls.size() - 1; index >= 0; index--) {
            ToolCall call = calls.get(index);
            history.add(ChatMessage.tool(call.id(), tools.dispatch(call)));
        }

        Transcript transcript = serialize(protocol, "reversed", history);

        assertEquals(List.of("call_a", "call_b", "call_c"), transcript.requestedIds());
        assertEquals(List.of("call_c", "call_b", "call_a"), transcript.resultIds());
        assertEquals(Map.of("call_a", "3", "call_b", "20", "call_c", "5"), transcript.results());
        assertTrue(transcript.unansweredIds().isEmpty());
    }

    private Transcript serialize(String protocol, String scenario, List<ChatMessage> history) throws IOException {
        ModelSettings settings = ModelSettings.defaults();
        Object request = switch (protocol) {
            case "chat" -> ChatRequest.forAgent(settings, history, calculator().definitions());
            case "responses" -> new ResponsesClient(json, "test", "https://api.example.test")
                    .request(history, calculator().definitions(), settings);
            case "anthropic" -> new AnthropicClient(json, "test", "https://api.example.test")
                    .request(history, calculator().definitions(), settings);
            default -> throw new IllegalArgumentException("unknown protocol: " + protocol);
        };
        JsonNode body = json.valueToTree(request);
        Path directory = Path.of("target", "tool-transcript-experiment");
        Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve(scenario + "-" + protocol + ".json").toFile(), body);

        Transcript transcript = new Transcript(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
        if ("chat".equals(protocol)) {
            for (JsonNode message : body.path("messages")) {
                for (JsonNode call : message.path("tool_calls")) {
                    transcript.requestedIds().add(call.path("id").asText());
                }
                if ("tool".equals(message.path("role").asText())) {
                    transcript.addResult(message.path("tool_call_id").asText(), message.path("content").asText());
                }
            }
        } else if ("responses".equals(protocol)) {
            for (JsonNode item : body.path("input")) {
                if ("function_call".equals(item.path("type").asText())) {
                    transcript.requestedIds().add(item.path("call_id").asText());
                } else if ("function_call_output".equals(item.path("type").asText())) {
                    transcript.addResult(item.path("call_id").asText(), item.path("output").asText());
                }
            }
        } else {
            // All three results belong in one user message, even when completed out of order.
            assertEquals(3, body.path("messages").size());
            assertEquals("user", body.at("/messages/2/role").asText());
            for (JsonNode message : body.path("messages")) {
                for (JsonNode block : message.path("content")) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        transcript.requestedIds().add(block.path("id").asText());
                    } else if ("tool_result".equals(block.path("type").asText())) {
                        transcript.addResult(block.path("tool_use_id").asText(), block.path("content").asText());
                    }
                }
            }
        }
        System.out.printf("%s/%s: requested=%s, returned=%s, unanswered=%s%n",
                scenario, protocol, transcript.requestedIds(), transcript.resultIds(), transcript.unansweredIds());
        return transcript;
    }

    private List<ChatMessage> startHistory() {
        return new ArrayList<>(List.of(ChatMessage.system("Use calculator for arithmetic."),
                ChatMessage.user("Calculate 1+2, 4*5, and 6-1."),
                ChatMessage.assistant(new AssistantTurn("", calls, "tool_calls"))));
    }

    private ToolRegistry calculator() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(json));
        return tools;
    }

    private static ToolCall call(String id, String expression) {
        return new ToolCall(id, "function", new ToolCall.FunctionCall("calculator",
                "{\"expression\":\"" + expression + "\"}"));
    }

    private record Transcript(List<String> requestedIds, List<String> resultIds, Map<String, String> results) {
        void addResult(String id, String result) {
            assertFalse(results.containsKey(id), "duplicate result ID: " + id);
            assertTrue(requestedIds.contains(id), "unknown result ID: " + id);
            resultIds.add(id);
            results.put(id, result);
        }

        List<String> unansweredIds() {
            return requestedIds.stream().filter(id -> !results.containsKey(id)).toList();
        }
    }
}
