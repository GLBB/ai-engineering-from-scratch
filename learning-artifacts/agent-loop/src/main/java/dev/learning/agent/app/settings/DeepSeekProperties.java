package dev.learning.agent.app.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DeepSeek connection settings supplied by Spring configuration. */
@ConfigurationProperties("agent.deepseek")
public record DeepSeekProperties(String apiKey, String baseUrl) {
    public DeepSeekProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("agent.deepseek.api-key is required (DEEPSEEK_API_KEY)");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agent.deepseek.base-url is required");
        }
    }

    @Override
    public String toString() {
        return "DeepSeekProperties[apiKey=<redacted>]";
    }
}
