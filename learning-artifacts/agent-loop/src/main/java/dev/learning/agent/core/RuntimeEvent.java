package dev.learning.agent.core;

/** Transport envelope; payloads are concrete records or existing text events. */
public record RuntimeEvent(String type, Object data) implements AgentEvent {}
