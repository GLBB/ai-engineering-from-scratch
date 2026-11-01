package dev.learning.agent.model;

/** Null means the provider did not report that metric. Preserve provider accounting. */
public record TokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens) {
    public static TokenUsage merge(TokenUsage first, TokenUsage next) {
        if (first == null) return next;
        if (next == null) return first;
        return new TokenUsage(next.inputTokens == null ? first.inputTokens : next.inputTokens,
                next.outputTokens == null ? first.outputTokens : next.outputTokens,
                next.cachedInputTokens == null ? first.cachedInputTokens : next.cachedInputTokens,
                next.reasoningTokens == null ? first.reasoningTokens : next.reasoningTokens);
    }
}
