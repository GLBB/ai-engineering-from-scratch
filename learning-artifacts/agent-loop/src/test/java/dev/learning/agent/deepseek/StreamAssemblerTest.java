package dev.learning.agent.deepseek;

import dev.learning.agent.deepseek.model.AssistantTurn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamAssemblerTest {
    @Test
    void mergesTextAndSplitToolArgumentsByIndex() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"I'll "},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"calculate."},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"calculator","arguments":"{\\\"expression\\\":\\\""}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1+2\\\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = new StreamAssembler(new ObjectMapper(), deltas::add)
                .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        assertEquals("I'll calculate.", turn.content());
        assertEquals(List.of("I'll ", "calculate."), deltas);
        assertEquals("tool_calls", turn.finishReason());
        assertEquals(1, turn.calls().size());
        assertEquals("call-1", turn.calls().get(0).id());
        assertEquals("calculator", turn.calls().get(0).function().name());
        assertEquals("{\"expression\":\"1+2\"}", turn.calls().get(0).function().arguments());
    }

    @Test
    void rejectsInterruptedStream() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
        assertThrows(IOException.class, () -> new StreamAssembler(new ObjectMapper(), ignored -> {})
                .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void collectsReasoningContentSeparatelyFromVisibleAnswer() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":"First "},"finish_reason":null}]}

                data: {"choices":[{"delta":{"reasoning_content":"think."},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"Answer"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        List<String> visible = new ArrayList<>();
        AssistantTurn turn = new StreamAssembler(new ObjectMapper(), visible::add)
                .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));

        assertEquals("First think.", turn.reasoningContent());
        assertEquals("Answer", turn.content());
        assertEquals(List.of("Answer"), visible);
    }
}
