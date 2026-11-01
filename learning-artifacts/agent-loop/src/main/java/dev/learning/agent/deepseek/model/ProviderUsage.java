package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderUsage(
        @JsonProperty("prompt_tokens") Long promptTokens,
        @JsonProperty("completion_tokens") Long completionTokens,
        @JsonProperty("input_tokens") Long inputTokens,
        @JsonProperty("output_tokens") Long outputTokens,
        @JsonProperty("prompt_cache_hit_tokens") Long promptCacheHitTokens,
        @JsonProperty("cache_read_input_tokens") Long cacheReadInputTokens,
        @JsonProperty("input_tokens_details") Details inputTokensDetails,
        @JsonProperty("output_tokens_details") Details outputTokensDetails,
        @JsonProperty("completion_tokens_details") Details completionTokensDetails) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(@JsonProperty("cached_tokens") Long cachedTokens,
                          @JsonProperty("reasoning_tokens") Long reasoningTokens) {}
}
