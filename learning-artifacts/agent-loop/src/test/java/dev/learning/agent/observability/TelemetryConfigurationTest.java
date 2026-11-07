package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TelemetryFactory;
import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.CompletionCheck;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.runtime.RunCounters;
import dev.learning.agent.model.ModelSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryConfigurationTest {
    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(BindingConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TracingProperties.class)
    static class BindingConfiguration {}

    @Test
    void yamlLoadsServerSettingsAndEnablesTracingByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(TracingProperties.class);
            assertTrue(properties.enabled());
            assertEquals(1.0, properties.sampleRatio());
            assertEquals("1.0-SNAPSHOT", properties.release());
            assertEquals("http://127.0.0.1:3035", properties.langfuse().baseUrl());
            assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
            assertEquals("UTF-8", context.getEnvironment().getProperty("server.servlet.encoding.charset"));
            assertEquals("true", context.getEnvironment().getProperty("server.servlet.encoding.force"));
            assertEquals("1200000", context.getEnvironment().getProperty("spring.mvc.async.request-timeout"));
            assertEquals("INFO", context.getEnvironment().getProperty("logging.level.org.springframework.web"));
        });
    }

    @Test
    void yamlPlaceholdersResolveExistingEnvironmentOverrides() {
        runner.withPropertyValues("AGENT_TRACING_ENABLED=true", "LANGFUSE_BASE_URL=https://traces.example.com",
            "LANGFUSE_PROJECT_ID=test-project", "AGENT_TRACE_SAMPLE_RATIO=0.25", "AGENT_RELEASE=test-release",
            "LANGFUSE_PUBLIC_KEY=public-sentinel", "LANGFUSE_SECRET_KEY=secret-sentinel").run(context -> {
                assertNull(context.getStartupFailure());
                var properties = context.getBean(TracingProperties.class);
                assertTrue(properties.enabled());
                assertEquals(0.25, properties.sampleRatio());
                assertEquals("test-release", properties.release());
                assertEquals("https://traces.example.com", properties.langfuse().baseUrl());
                assertEquals("test-project", properties.langfuse().projectId());
                assertEquals("public-sentinel", properties.langfuse().publicKey());
                assertEquals("secret-sentinel", properties.langfuse().secretKey());
                assertFalse(properties.toString().contains("secret-sentinel"));
                assertFalse(properties.toString().contains("public-sentinel"));
            });
    }

    @Test
    void springPropertiesOverrideYamlValues() {
        runner.withPropertyValues("agent.tracing.enabled=true", "agent.tracing.sample-ratio=0.5",
            "agent.tracing.langfuse.base-url=https://configured.example.com").run(context -> {
                assertNull(context.getStartupFailure());
                var properties = context.getBean(TracingProperties.class);
                assertTrue(properties.enabled());
                assertEquals(0.5, properties.sampleRatio());
                assertEquals("https://configured.example.com", properties.langfuse().baseUrl());
            });
    }

    @Test
    void disabledSpringBeanNeedsNoCredentials() {
        runner.withUserConfiguration(TelemetryConfiguration.class).withPropertyValues("agent.tracing.enabled=false")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertSame(TelemetryFactory.NOOP, context.getBean(TelemetryFactory.class));
            });
    }

    @Test
    void enabledSpringBeanExportsContentToConfiguredLangfuseEndpoint() throws Exception {
        var received = new CompletableFuture<ExportRequest>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            try {
                received.complete(new ExportRequest(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            runner.withUserConfiguration(TelemetryConfiguration.class)
                .withPropertyValues("agent.tracing.enabled=true", "agent.tracing.sample-ratio=1.0",
                    "agent.tracing.langfuse.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "agent.tracing.langfuse.public-key=public-sentinel",
                    "agent.tracing.langfuse.secret-key=secret-sentinel")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var factory = context.getBean(TelemetryFactory.class);
                    var request =
                        new ExecutionRequest(UUID.randomUUID(), "question-sentinel", ModelSettings.defaults());
                    var trace = factory.open("test-session", request, AgentMode.REACT, "test");
                    trace.completed(new RunResult(ExecutionStatus.COMPLETE, CompletionCheck.UNVERIFIED,
                        "answer-sentinel", null, new RunCounters(0, 0, 0, 0, 0)));
                    trace.end();
                    var exported = received.get(5, TimeUnit.SECONDS);
                    assertEquals("POST", exported.method());
                    String credentials = Base64.getEncoder()
                        .encodeToString("public-sentinel:secret-sentinel".getBytes(StandardCharsets.UTF_8));
                    assertEquals("Basic " + credentials, exported.authorization());
                    assertEquals("4", exported.ingestionVersion());
                    assertTrue(exported.body().contains("question-sentinel"));
                    assertTrue(exported.body().contains("answer-sentinel"));
                    assertFalse(exported.body().contains("agent_loop.content.mode"));
                });
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://user:secret-sentinel@example.com",
        "https://example.com?secret=secret-sentinel", "https://example.com#secret-sentinel", "invalid url"})
    void unsafeEndpointsAreRejectedWithoutPrintingCredentials(String endpoint) {
        var properties = properties(endpoint, 1.0);
        var error = assertThrows(IllegalArgumentException.class, () -> OtelTelemetryFactory.fromProperties(properties));
        assertFalse(error.getMessage().contains("secret-sentinel"));
        assertTrue(error.getMessage().contains("agent.tracing.langfuse.base-url"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidSamplingRatiosAreRejected(double ratio) {
        var error = assertThrows(IllegalArgumentException.class,
            () -> OtelTelemetryFactory.fromProperties(properties("http://127.0.0.1:3035", ratio)));
        assertEquals("agent.tracing.sample-ratio must be 0..1", error.getMessage());
    }

    @Test
    void enabledTracingRequiresConfiguredCredentials() {
        var error = assertThrows(IllegalArgumentException.class,
            () -> OtelTelemetryFactory.fromProperties(properties("http://127.0.0.1:3035", 1.0)));
        assertEquals("agent.tracing.langfuse.public-key is required when tracing is enabled", error.getMessage());
    }

    private static TracingProperties properties(String endpoint, double ratio) {
        return new TracingProperties(true, ratio, "test-release",
            new TracingProperties.Langfuse(endpoint, "test-project", "", ""));
    }

    private record ExportRequest(String method, String authorization, String ingestionVersion, String body) {
    }
}
