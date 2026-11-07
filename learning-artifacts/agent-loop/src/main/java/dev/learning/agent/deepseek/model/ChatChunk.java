package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatChunk(List<ChunkChoice> choices, ProviderUsage usage, String id, String model) {
    public ChatChunk(List<ChunkChoice> choices, ProviderUsage usage) { this(choices, usage, null, null); }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(ChunkDelta delta, @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkDelta(String content,
                             @JsonProperty("reasoning_content") String reasoningContent,
                             @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(int index, String id, String type, FunctionDelta function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDelta(String name, String arguments) {}
}
