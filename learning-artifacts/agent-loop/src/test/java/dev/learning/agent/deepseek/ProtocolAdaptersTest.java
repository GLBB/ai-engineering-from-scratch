package dev.learning.agent.deepseek;

import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ModelSettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolAdaptersTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResponsesClient responses = new ResponsesClient(json, "test", "https://api.example.test");
    private final AnthropicClient anthropic = new AnthropicClient(json, "test", "https://api.example.test");

    @Test
    void responsesReadsToolTurnAndReplaysFullHistory() throws Exception {
        String events = """
            data: {"type":"response.output_text.delta","delta":"Checking"}

            data: {"type":"response.completed","response":{"output":[{"type":"reasoning","content\
            ":[{"type":"reasoning_text","text":"Calculate"}]},{"type":"message","content":[{"type\
            ":"output_text","text":"Checking"}]},{"type":"function_call","call_id":"call-1","name\
            ":"calculator","arguments":"{\\"expression\\":\\"1+2\\"}"}]}}

            """;
        List<String> streamed = new ArrayList<>();
        AssistantTurn turn = responses.read(input(events), streamed::add);
        assertEquals(List.of("Checking"), streamed);
        assertEquals("Calculate", turn.reasoningContent());
        assertEquals("tool_calls", turn.finishReason());
        assertEquals("call-1", turn.calls().get(0).id());

        var request = responses.request(List.of(ChatMessage.system("system"), ChatMessage.user("question"),
            ChatMessage.assistant(turn), ChatMessage.tool("call-1", "3")), List.of(),
            new ModelSettings("deepseek-flash", "high"));
        JsonNode body = json.valueToTree(request);
        assertEquals("high", body.at("/reasoning/effort").asText());
        assertEquals("reasoning", body.at("/input/2/type").asText());
        assertEquals("function_call", body.at("/input/4/type").asText());
        assertEquals("call-1", body.at("/input/5/call_id").asText());
        assertEquals("3", body.at("/input/5/output").asText());
    }

    @Test
    void responsesOffAndIncompleteStream() throws Exception {
        JsonNode body =
            json.valueToTree(responses.request(List.of(ChatMessage.user("hi")), List.of(), ModelSettings.defaults()));
        assertEquals("none", body.at("/reasoning/effort").asText());
        assertThrows(java.io.IOException.class,
            () -> responses.read(input("data: {\"type\":\"response.created\"}\n\n"), ignored -> {
            }));
        assertThrows(java.io.IOException.class,
            () -> responses.read(input("data: {\"type\":\"response.incomplete\"}\n\n"), ignored -> {
            }));
    }

    @Test
    void anthropicPreservesThinkingSignatureAndGroupsToolResults() throws Exception {
        String events = """
            data: {"type":"message_start"}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Calculate"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}

            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id"\
            :"one","name":"calculator","input":{}}}

            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","par\
            tial_json":"{\\"expression\\":\\"1+2\\"}"}}

            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id"\
            :"two","name":"calculator","input":{}}}

            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","par\
            tial_json":"{\\"expression\\":\\"2+2\\"}"}}

            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

            data: {"type":"message_stop"}

            """;
        AssistantTurn turn = anthropic.read(input(events), ignored -> {
        });
        assertEquals("Calculate", turn.reasoningContent());
        assertEquals(2, turn.calls().size());
        assertEquals("signed", turn.blocks().get(0).signature());
        var request = anthropic.request(
            List.of(ChatMessage.system("system"), ChatMessage.user("question"), ChatMessage.assistant(turn),
                ChatMessage.tool("one", "3"), ChatMessage.tool("two", "4")),
            List.of(), new ModelSettings("deepseek-flash", "high"));
        JsonNode body = json.valueToTree(request);
        assertEquals("enabled", body.at("/thinking/type").asText());
        assertEquals("high", body.at("/output_config/effort").asText());
        assertEquals("signed", body.at("/messages/1/content/0/signature").asText());
        assertEquals("1+2", body.at("/messages/1/content/1/input/expression").asText());
        assertEquals("user", body.at("/messages/2/role").asText());
        assertEquals("one", body.at("/messages/2/content/0/tool_use_id").asText());
        assertEquals("two", body.at("/messages/2/content/1/tool_use_id").asText());
        assertEquals(3, body.path("messages").size());
    }

    @Test
    void anthropicStreamsTextAndRejectsTruncation() throws Exception {
        String events = """
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            data: {"type":"message_stop"}

            """;
        List<String> streamed = new ArrayList<>();
        AssistantTurn turn = anthropic.read(input(events), streamed::add);
        assertEquals(List.of("hello"), streamed);
        assertEquals("hello", turn.content());
        assertEquals("stop", turn.finishReason());
        assertThrows(java.io.IOException.class,
            () -> anthropic.read(input(events.replace("message_stop", "ping")), ignored -> {
            }));
        JsonNode body =
            json.valueToTree(anthropic.request(List.of(ChatMessage.user("hi")), List.of(), ModelSettings.defaults()));
        assertEquals("disabled", body.at("/thinking/type").asText());
        assertFalse(body.has("output_config"));
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
