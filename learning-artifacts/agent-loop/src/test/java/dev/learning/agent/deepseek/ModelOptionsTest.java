package dev.learning.agent.deepseek;

import dev.learning.agent.deepseek.model.ChatRequest;
import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.model.OutputMode;
import dev.learning.agent.model.TokenUsage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelOptionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelRequest planner = new ModelRequest(List.of(ChatMessage.user("Return a JSON plan.")),
            ModelSettings.defaults(), List.of(), OutputMode.JSON_OBJECT, 1024, ModelUse.CONTROL);

    @Test void chatPlannerHasFormatAndBudgetWithoutNativeTools() {
        var wire = json.valueToTree(ChatRequest.forModel(planner));
        assertFalse(wire.has("tools"));
        assertFalse(wire.has("tool_choice"));
        assertEquals(1024, wire.path("max_tokens").asInt());
        assertEquals("json_object", wire.at("/response_format/type").asText());
    }

    @Test void responsesPlannerOmitsToolChoiceAndSetsItsOwnBudgetField() {
        var wire = json.valueToTree(new ResponsesClient(json, "test", "https://api.example.test").request(planner));
        assertFalse(wire.has("tools"));
        assertFalse(wire.has("tool_choice"));
        assertEquals(1024, wire.path("max_output_tokens").asInt());
    }

    @Test void anthropicPlannerOmitsToolsAndKeepsExplicitBudget() throws Exception {
        var wire = json.valueToTree(new AnthropicClient(json, "test", "https://api.example.test").request(planner));
        assertFalse(wire.has("tools"));
        assertEquals(1024, wire.path("max_tokens").asInt());
    }

    @Test void streamingUsageRemainsDistinctFromOutputText() throws Exception {
        String frames = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":7,\"prompt_cache_hit_tokens\":10,\"completion_tokens_details\":{\"reasoning_tokens\":3}}}\n\n"
                + "data: [DONE]\n\n";
        var answer = new StreamAssembler(json, ignored -> {}).read(new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)));
        assertEquals("ok", answer.content());
        assertEquals(new TokenUsage(20L, 7L, 10L, 3L, null, TokenUsage.InputAccounting.INCLUDES_CACHE), answer.usage());
        assertEquals(20L, answer.usage().totalInputTokens());
    }

    @Test void hugeReasoningIsRejectedEvenWithoutVisibleText() {
        String frames = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"" + "x".repeat(16001)
                + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        assertThrows(java.io.IOException.class, () -> new StreamAssembler(json, ignored -> {})
                .read(new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8))));
    }
}
