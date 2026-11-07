package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.model.TokenUsage;
import dev.learning.agent.model.ModelStreamObserver;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StreamObservationTest {
    private final ObjectMapper json=new ObjectMapper();
    private static ByteArrayInputStream input(String value) {return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
    @Test void chatIgnoresEmptyChunksAndReportsReasoningBeforeVisibleText() throws Exception {
        String frames="""
                data: {"id":"response-real","model":"deepseek-flash","choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"reasoning_content":"reason"}}]}

                data: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}

                data: [DONE]

                """;
        List<Boolean> chunks=new ArrayList<>();
        List<Boolean> completeUsage=new ArrayList<>();
        var observer = new ModelStreamObserver() {
            public void chunk(boolean text) { chunks.add(text); }
            public void usage(TokenUsage usage, boolean complete) { completeUsage.add(complete); }
        };
        var result=new StreamAssembler(json,text->{},observer).read(input(frames));
        assertEquals(List.of(false,true),chunks);
        assertEquals(List.of(false,true),completeUsage);
        assertEquals(12L,result.usage().inputTokens());
        assertEquals("response-real",result.metadata().id());
    }
    @Test void responsesCreatedAndPingAreNotModelOutputChunks() throws Exception {
        String frames="""
                data: {"type":"response.created"}

                data: {"type":"response.function_call_arguments.delta","delta":"{}"}

                data: {"type":"response.completed","response":{"id":"response-real","model":"deepseek-flash","output":[]}}

                """;
        List<Boolean> chunks=new ArrayList<>();
        var result=new ResponsesClient(json,"fixture-key","https://example.test").read(input(frames),text->{},chunks::add);
        assertEquals(List.of(false),chunks);
        assertEquals("response-real",result.metadata().id());
    }
    @Test void anthropicFinalUsageReplacesInitialCountsAndAddsCacheOnlyToInputTotal() throws Exception {
        String frames="""
                data: {"type":"message_start","message":{"id":"message-real","model":"deepseek-flash","usage":{"input_tokens":1,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}

                data: {"type":"ping"}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":60,"output_tokens":20,"cache_read_input_tokens":40,"cache_creation_input_tokens":0}}

                data: {"type":"message_stop"}

                """;
        List<Boolean> chunks=new ArrayList<>();
        var result=new AnthropicClient(json,"fixture-key","https://example.test").read(input(frames),text->{},chunks::add);
        assertEquals(List.of(true),chunks);
        assertEquals(60L,result.usage().inputTokens());
        assertEquals(100L,result.usage().totalInputTokens());
        assertEquals(20L,result.usage().outputTokens());
        assertEquals(TokenUsage.InputAccounting.EXCLUDES_CACHE,result.usage().inputAccounting());
        assertEquals("message-real",result.metadata().id());
    }
}
