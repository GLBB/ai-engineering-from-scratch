package dev.learning.agent.model;

/** Null means the provider did not report that metric. Preserve provider accounting. */
public record TokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens,
    Long cacheWriteInputTokens, InputAccounting inputAccounting) {
    public enum InputAccounting {
        INCLUDES_CACHE, EXCLUDES_CACHE, UNKNOWN
    }

    public TokenUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens) {
        this(inputTokens, outputTokens, cachedInputTokens, reasoningTokens, null, InputAccounting.UNKNOWN);
    }

    public Long totalInputTokens() {
        if (inputTokens == null || inputTokens < 0 || inputAccounting == InputAccounting.UNKNOWN) {
            return null;
        }
        if (inputAccounting == InputAccounting.INCLUDES_CACHE) {
            return inputTokens;
        }
        if (cachedInputTokens == null || cacheWriteInputTokens == null || cachedInputTokens < 0
            || cacheWriteInputTokens < 0) {
            return null;
        }
        return inputTokens + cachedInputTokens + cacheWriteInputTokens;
    }

    public static TokenUsage merge(TokenUsage first, TokenUsage next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        return new TokenUsage(next.inputTokens == null ? first.inputTokens : next.inputTokens,
            next.outputTokens == null ? first.outputTokens : next.outputTokens,
            next.cachedInputTokens == null ? first.cachedInputTokens : next.cachedInputTokens,
            next.reasoningTokens == null ? first.reasoningTokens : next.reasoningTokens,
            next.cacheWriteInputTokens == null ? first.cacheWriteInputTokens : next.cacheWriteInputTokens,
            next.inputAccounting == InputAccounting.UNKNOWN ? first.inputAccounting : next.inputAccounting);
    }
}
