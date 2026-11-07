package dev.learning.agent.observability;

import dev.learning.agent.model.TokenUsage;
import dev.learning.agent.support.json.StrictJson;
import com.fasterxml.jackson.annotation.JsonInclude;

/** OTel totals include subsets; Langfuse usage details must instead be non-overlapping. */
final class UsageMapping {
    private UsageMapping() {
    }

    static void apply(OtelSpan span, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        span.attribute("agent_loop.model.usage_accounting", usage.inputAccounting().name());
        Long input = usage.totalInputTokens();
        Long output = valid(usage.outputTokens());
        Long cached = valid(usage.cachedInputTokens());
        Long written = valid(usage.cacheWriteInputTokens());
        Long reasoning = valid(usage.reasoningTokens());
        span.attribute("agent_loop.model.raw_usage", StrictJson.write(usage));
        if (input != null && (cached == null ? 0 : cached) + (written == null ? 0 : written) > input
            || output != null && reasoning != null && reasoning > output) {
            span.event("agent_loop.usage.invalid", "INCONSISTENT_SUBSETS");
            return;
        }
        put(span, "gen_ai.usage.input_tokens", input);
        put(span, "gen_ai.usage.output_tokens", output);
        put(span, "gen_ai.usage.cache_read.input_tokens", cached);
        put(span, "gen_ai.usage.cache_write.input_tokens", written);
        put(span, "gen_ai.usage.reasoning.output_tokens", reasoning);
        if (input == null || output == null) {
            return;
        }
        long read = cached == null ? 0 : cached;
        long write = written == null ? 0 : written;
        long thought = reasoning == null ? 0 : reasoning;
        span.attribute("langfuse.observation.usage_details", StrictJson
            .write(new Usage(input - read - write, output - thought, cached, written, reasoning, input + output)));
    }

    private static Long valid(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private static void put(OtelSpan span, String key, Long value) {
        if (value != null) {
            span.attribute(key, value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Usage(long input, long output, Long cache_read_input_tokens, Long cache_creation_input_tokens,
        Long reasoning_output_tokens, long total) {
    }
}
