package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TelemetryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelemetryConfiguration {
    @Bean(destroyMethod = "close")
    TelemetryFactory telemetryFactory() { return OtelTelemetryFactory.fromEnvironment(System.getenv()); }
}
