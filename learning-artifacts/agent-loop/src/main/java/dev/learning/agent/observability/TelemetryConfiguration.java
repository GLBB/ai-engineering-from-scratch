package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TelemetryFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TracingProperties.class)
public class TelemetryConfiguration {
    @Bean(destroyMethod = "close")
    TelemetryFactory telemetryFactory(TracingProperties properties) {
        return OtelTelemetryFactory.fromProperties(properties);
    }
}
