package dev.learning.agent.model;

import java.util.List;

/** Model and reasoning level selected for one user request. */
public record ModelSettings(String model, String reasoningLevel) {
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final List<String> MODELS = List.of("deepseek-flash", "deepseek-v4-pro");
    public static final List<String> LEVELS = List.of("off", "low", "high", "max");

    public ModelSettings {
        if (!MODELS.contains(model)) {
            throw new IllegalArgumentException("unsupported model");
        }
        if (!LEVELS.contains(reasoningLevel)) {
            throw new IllegalArgumentException("unsupported reasoning level");
        }
    }

    public static ModelSettings defaults() {
        return new ModelSettings(DEFAULT_MODEL, "off");
    }

    public boolean thinking() {
        return !"off".equals(reasoningLevel);
    }

    public String apiReasoningEffort() {
        return thinking() ? reasoningLevel : null;
    }

    public String summary() {
        return model + " · 推理等级 " + reasoningLevel;
    }
}
