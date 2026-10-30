package dev.learning.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
                   @JsonProperty("tool_choice") String toolChoice,
                   boolean stream, Thinking thinking) {}

record Thinking(String type) {}
