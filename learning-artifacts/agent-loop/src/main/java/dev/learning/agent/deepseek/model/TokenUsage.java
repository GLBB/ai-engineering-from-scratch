package dev.learning.agent.deepseek.model;

/** Null means the provider did not report that metric. Preserve provider accounting. */
public record TokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens) {
    public static TokenUsage from(ProviderUsage usage) {
        if (usage == null) return null;
        Long input = usage.promptTokens() != null ? usage.promptTokens() : usage.inputTokens();
        Long output = usage.completionTokens() != null ? usage.completionTokens() : usage.outputTokens();
        Long cached = usage.promptCacheHitTokens() != null ? usage.promptCacheHitTokens() : usage.cacheReadInputTokens();
        if (cached == null && usage.inputTokensDetails() != null) cached = usage.inputTokensDetails().cachedTokens();
        Long reasoning = usage.completionTokensDetails() == null ? null : usage.completionTokensDetails().reasoningTokens();
        if (reasoning == null && usage.outputTokensDetails() != null) reasoning = usage.outputTokensDetails().reasoningTokens();
        return new TokenUsage(input, output, cached, reasoning);
    }

    public static TokenUsage merge(TokenUsage first, TokenUsage next) {
        if (first == null) return next;
        if (next == null) return first;
        return new TokenUsage(next.inputTokens == null ? first.inputTokens : next.inputTokens,
                next.outputTokens == null ? first.outputTokens : next.outputTokens,
                next.cachedInputTokens == null ? first.cachedInputTokens : next.cachedInputTokens,
                next.reasoningTokens == null ? first.reasoningTokens : next.reasoningTokens);
    }
}
