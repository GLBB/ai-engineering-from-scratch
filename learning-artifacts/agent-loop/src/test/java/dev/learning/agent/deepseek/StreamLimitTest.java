package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamLimitTest {
    @Test
    void frameLimitCountsUtf8BytesInsteadOfCharacters() {
        String input = "data: " + "汉".repeat(44_000) + "\n";
        assertThrows(IOException.class, () -> StreamingHttp.readLine(new BufferedReader(new StringReader(input))));
    }

    @Test
    void multilineEventsCannotEvadePerFrameLimit() {
        String event = ("data: " + "x".repeat(70_000) + "\n").repeat(2) + "\n";
        assertThrows(IOException.class, () -> StreamingHttp.readEvents(bytes(event), ignored -> {
        }));
        assertThrows(IOException.class, () -> new StreamAssembler(new ObjectMapper(), ignored -> {
        }).read(bytes(event)));
    }

    @Test
    void responsesReasoningIsBoundedBeforeCompletionEvent() {
        String event =
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"" + "x".repeat(16_001) + "\"}\n\n";
        IOException error = assertThrows(IOException.class,
            () -> new ResponsesClient(new ObjectMapper(), "test", "https://example.test").read(bytes(event),
                ignored -> {
                }));
        assertEquals("model output too large", error.getMessage());
    }

    @Test
    void anthropicThinkingIsBoundedBeforeMessageStop() {
        String event = "data: {\"type\":\"content_block_start\",\"index\":0,\"content_blo"
            + "ck\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\""
            + "type\":\"thinking_delta\",\"thinking\":\"" + "x".repeat(16_001) + "\"}}\n\n";
        IOException error = assertThrows(IOException.class,
            () -> new AnthropicClient(new ObjectMapper(), "test", "https://example.test").read(bytes(event),
                ignored -> {
                }));
        assertEquals("model output too large", error.getMessage());
    }

    private InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
