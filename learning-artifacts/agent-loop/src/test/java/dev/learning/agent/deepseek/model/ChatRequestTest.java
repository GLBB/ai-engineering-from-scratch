package dev.learning.agent.deepseek.model;

import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ToolSpec;
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
        ChatRequest request =
            ChatRequest.forAgent(ModelSettings.defaults(), List.of(ChatMessage.user("2+2")), List.of(calculator));
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));
        assertEquals("deepseek-flash", wire.get("model").asText());
        assertEquals("user", wire.get("messages").get(0).get("role").asText());
        assertEquals("auto", wire.get("tool_choice").asText());
        assertTrue(wire.get("stream").asBoolean());
        assertEquals("disabled", wire.get("thinking").get("type").asText());
        assertTrue(!wire.has("reasoning_effort"));
        assertTrue(!wire.has("max_tokens"));
        assertEquals("string", wire.get("tools").get(0).get("function").get("parameters").get("properties")
            .get("expression").get("type").asText());
    }

    @Test
    void sendsThinkingModeAndSelectedEffort() throws Exception {
        ModelSettings settings = new ModelSettings("deepseek-v4-pro", "max");
        ChatRequest request = ChatRequest.forAgent(settings, List.of(ChatMessage.user("hello")), List.of());
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));

        assertEquals("deepseek-v4-pro", wire.get("model").asText());
        assertEquals("enabled", wire.get("thinking").get("type").asText());
        assertEquals("max", wire.get("reasoning_effort").asText());
    }

    @Test
    void rejectsUnknownReasoningLevel() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ModelSettings("deepseek-flash", "ultra"));
    }

    @Test
    void offDisablesThinkingWithoutReasoningEffort() throws Exception {
        ChatRequest request = ChatRequest.forAgent(new ModelSettings("deepseek-flash", "off"),
            List.of(ChatMessage.user("hello")), List.of());
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));

        assertEquals("disabled", wire.get("thinking").get("type").asText());
        assertTrue(!wire.has("reasoning_effort"));
    }

    @Test
    void serializesReasoningForLaterToolRequests() throws Exception {
        ChatMessage assistant = ChatMessage.assistant("", List.of(), "Use calculator first.");
        ChatRequest request =
            ChatRequest.forAgent(new ModelSettings("deepseek-flash", "high"), List.of(assistant), List.of());
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));

        assertEquals("Use calculator first.", wire.get("messages").get(0).get("reasoning_content").asText());
    }
}
