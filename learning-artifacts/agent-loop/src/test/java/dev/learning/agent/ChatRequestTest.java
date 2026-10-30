package dev.learning.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {
    @Test
    void serializesTypedMessagesAndParameterSchema() throws Exception {
        ToolSpec calculator = new CalculatorTool(new ObjectMapper()).definition();
        ChatRequest request = new ChatRequest("deepseek-flash",
                List.of(ChatMessage.user("2+2")), List.of(calculator),
                "auto", true, new Thinking("disabled"));
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));
        assertEquals("deepseek-flash", wire.get("model").asText());
        assertEquals("user", wire.get("messages").get(0).get("role").asText());
        assertEquals("auto", wire.get("tool_choice").asText());
        assertTrue(wire.get("stream").asBoolean());
        assertEquals("string", wire.get("tools").get(0).get("function")
                .get("parameters").get("properties").get("expression").get("type").asText());
    }
}
