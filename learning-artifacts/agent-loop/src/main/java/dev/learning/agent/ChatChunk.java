package dev.learning.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record ChatChunk(List<ChunkChoice> choices) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record ChunkChoice(ChunkDelta delta, @JsonProperty("finish_reason") String finishReason) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record ChunkDelta(String content, @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record ToolCallDelta(int index, String id, String type, FunctionDelta function) {}

@JsonIgnoreProperties(ignoreUnknown = true)
record FunctionDelta(String name, String arguments) {}
