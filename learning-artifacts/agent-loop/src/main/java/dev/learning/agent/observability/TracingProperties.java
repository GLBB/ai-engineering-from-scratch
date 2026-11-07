package dev.learning.agent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trace settings bound from application.yml and Spring overrides. */
@ConfigurationProperties("agent.tracing")
public record TracingProperties(boolean enabled, double sampleRatio, String release, Langfuse langfuse) {
    public record Langfuse(String baseUrl, String projectId, String publicKey, String secretKey) {
        @Override
        public String toString() {
            return "Langfuse[baseUrl=" + baseUrl + ", projectId=" + projectId + "]";
        }
    }
}
