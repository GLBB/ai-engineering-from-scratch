package dev.learning.agent.deepseek.model;

import dev.learning.agent.model.TokenUsage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderUsage(@JsonProperty("prompt_tokens") Long promptTokens,
    @JsonProperty("completion_tokens") Long completionTokens, @JsonProperty("input_tokens") Long inputTokens,
    @JsonProperty("output_tokens") Long outputTokens,
    @JsonProperty("prompt_cache_hit_tokens") Long promptCacheHitTokens,
    @JsonProperty("cache_read_input_tokens") Long cacheReadInputTokens,
    @JsonProperty("input_tokens_details") Details inputTokensDetails,
    @JsonProperty("output_tokens_details") Details outputTokensDetails,
    @JsonProperty("completion_tokens_details") Details completionTokensDetails,
    @JsonProperty("cache_creation_input_tokens") Long cacheCreationInputTokens) {

    public static TokenUsage toAnthropicUsage(ProviderUsage usage) {
        TokenUsage value = toTokenUsage(usage);
        return value == null ? null
            : new TokenUsage(value.inputTokens(), value.outputTokens(), value.cachedInputTokens(),
                value.reasoningTokens(), value.cacheWriteInputTokens(), TokenUsage.InputAccounting.EXCLUDES_CACHE);
    }

    public static TokenUsage toTokenUsage(ProviderUsage usage) {
        if (usage == null) {
            return null;
        }
        Long input = usage.promptTokens() != null ? usage.promptTokens() : usage.inputTokens();
        Long output = usage.completionTokens() != null ? usage.completionTokens() : usage.outputTokens();
        Long cached =
            usage.promptCacheHitTokens() != null ? usage.promptCacheHitTokens() : usage.cacheReadInputTokens();
        if (cached == null && usage.inputTokensDetails() != null) {
            cached = usage.inputTokensDetails().cachedTokens();
        }
        Long reasoning =
            usage.completionTokensDetails() == null ? null : usage.completionTokensDetails().reasoningTokens();
        if (reasoning == null && usage.outputTokensDetails() != null) {
            reasoning = usage.outputTokensDetails().reasoningTokens();
        }
        return new TokenUsage(input, output, cached, reasoning, usage.cacheCreationInputTokens(),
            TokenUsage.InputAccounting.INCLUDES_CACHE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(@JsonProperty("cached_tokens") Long cachedTokens,
        @JsonProperty("reasoning_tokens") Long reasoningTokens) {
    }
}
