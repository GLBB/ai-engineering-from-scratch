package dev.learning.agent.app.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional Exa credentials supplied by Spring configuration. */
@ConfigurationProperties("agent.exa")
public record ExaProperties(String apiKey, String fallbackApiKey) {
    public ExaProperties {
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = fallbackApiKey;
        }
    }

    @Override
    public String toString() {
        return "ExaProperties[apiKey=<redacted>, fallbackApiKey=<redacted>]";
    }
}
