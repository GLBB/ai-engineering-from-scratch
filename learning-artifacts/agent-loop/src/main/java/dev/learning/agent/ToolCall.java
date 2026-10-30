package dev.learning.agent;

record ToolCall(String id, String type, FunctionCall function) {}

record FunctionCall(String name, String arguments) {}
