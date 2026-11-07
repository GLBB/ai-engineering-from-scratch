package dev.learning.agent.observability;

import dev.learning.agent.model.TokenUsage;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.telemetry.LangfuseAttributes;
import dev.learning.agent.telemetry.SpanAttributes;
import com.fasterxml.jackson.annotation.JsonInclude;

/** OTel totals include subsets; Langfuse usage details must instead be non-overlapping. */
final class UsageMapping {
    private UsageMapping() {
    }

    static void apply(OtelSpan span, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        span.attributes(new SpanAttributes.RawUsage(usage.inputAccounting().name(), StrictJson.write(usage)));
        Long input = usage.totalInputTokens();
        Long output = valid(usage.outputTokens());
        Long cached = valid(usage.cachedInputTokens());
        Long written = valid(usage.cacheWriteInputTokens());
        Long reasoning = valid(usage.reasoningTokens());
        if (input != null && (cached == null ? 0 : cached) + (written == null ? 0 : written) > input
            || output != null && reasoning != null && reasoning > output) {
            span.event("agent_loop.usage.invalid", "INCONSISTENT_SUBSETS");
            return;
        }
        span.attributes(new SpanAttributes.TokenUsage(input, output, cached, written, reasoning));
        if (input == null || output == null) {
            return;
        }
        long read = cached == null ? 0 : cached;
        long write = written == null ? 0 : written;
        long thought = reasoning == null ? 0 : reasoning;
        span.attributes(new LangfuseAttributes.UsageDetails(StrictJson
            .write(new Usage(input - read - write, output - thought, cached, written, reasoning, input + output))));
    }

    private static Long valid(Long value) {
        return value == null || value < 0 ? null : value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Usage(long input, long output, Long cache_read_input_tokens, Long cache_creation_input_tokens,
        Long reasoning_output_tokens, long total) {
    }
}
