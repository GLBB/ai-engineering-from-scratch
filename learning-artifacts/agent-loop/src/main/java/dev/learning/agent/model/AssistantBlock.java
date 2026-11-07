package dev.learning.agent.model;

/** One ordered Anthropic assistant content block, including its tool input or thinking signature. */
public record AssistantBlock(String type, String text, String thinking, String signature, String id, String name,
    String input) {
}
