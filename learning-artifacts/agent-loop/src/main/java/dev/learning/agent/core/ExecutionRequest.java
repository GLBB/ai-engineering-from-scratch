package dev.learning.agent.core;

import dev.learning.agent.model.ModelSettings;

import java.util.UUID;

public record ExecutionRequest(UUID requestId, String prompt, ModelSettings settings) {}
