package dev.learning.agent.deepseek.model;

import dev.learning.agent.tool.CalculatorTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {
    @Test
    void serializesTypedMessagesAndParameterSchema() throws Exception {
        ToolSpec calculator = new CalculatorTool(new ObjectMapper()).definition();
        ChatRequest request = ChatRequest.forAgent("deepseek-flash",
                List.of(ChatMessage.user("2+2")), List.of(calculator));
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));
        assertEquals("deepseek-flash", wire.get("model").asText());
        assertEquals("user", wire.get("messages").get(0).get("role").asText());
        assertEquals("auto", wire.get("tool_choice").asText());
        assertTrue(wire.get("stream").asBoolean());
        assertEquals("disabled", wire.get("thinking").get("type").asText());
        assertTrue(!wire.has("max_tokens"));
        assertEquals("string", wire.get("tools").get(0).get("function")
                .get("parameters").get("properties").get("expression").get("type").asText());
    }
}
