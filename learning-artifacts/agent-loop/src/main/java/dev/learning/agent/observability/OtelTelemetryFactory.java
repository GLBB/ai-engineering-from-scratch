package dev.learning.agent.observability;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.runtime.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Owns one local SDK, without registering or shutting down any global provider. */
public final class OtelTelemetryFactory implements TelemetryFactory {
    static final ContextKey<OtelRunTelemetry> RUN = ContextKey.named("agent-loop-run");
    public static final String SEMCONV = "244465e94b9d7167fa5ed5c31a4b2b49ab6ddee5";
    private final Tracer tracer;
    private final TraceContentPolicy content;
    private final String baseUrl;
    private final String projectId;
    private final String release;
    private final SdkTracerProvider ownedProvider;
    private final ExternalObserver external = new ExternalObserver() {
        public TraceSpan operation(String name) {
            var run = Context.current().get(RUN);
            return run == null ? TraceSpan.NOOP : run.operation(name, "span", SpanKind.INTERNAL, null);
        }
        public TraceSpan http(String method, URI endpoint) {
            var run = Context.current().get(RUN);
            if (run == null) return TraceSpan.NOOP;
            TraceSpan span = run.operation(method, "span", SpanKind.CLIENT, null);
            span.attribute("http.request.method", method);
            span.attribute("server.address", endpoint.getHost());
            span.attribute("server.port", endpoint.getPort() < 0 ? ("https".equals(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort());
            span.attribute("url.scheme", endpoint.getScheme());
            span.attribute("url.path", endpoint.getPath());
            return span;
        }
    };

    public OtelTelemetryFactory(OpenTelemetry sdk, TraceContentPolicy content, String baseUrl, String projectId, String release) {
        this(sdk, content, baseUrl, projectId, release, null);
    }
    private OtelTelemetryFactory(OpenTelemetry sdk, TraceContentPolicy content, String baseUrl, String projectId,
                                 String release, SdkTracerProvider ownedProvider) {
        tracer = sdk.getTracer("dev.learning.agent", "1.0");
        this.content = content; this.baseUrl = baseUrl; this.projectId = projectId;
        this.release = release; this.ownedProvider = ownedProvider;
    }
    public static TelemetryFactory fromEnvironment(Map<String, String> env) {
        String enabled = env.getOrDefault("AGENT_TRACING_ENABLED", "false");
        if (!Set.of("true", "false").contains(enabled)) throw new IllegalArgumentException("AGENT_TRACING_ENABLED must be true or false");
        if (!Boolean.parseBoolean(enabled)) return TelemetryFactory.NOOP;
        String base = env.getOrDefault("LANGFUSE_BASE_URL", "http://127.0.0.1:3035").replaceAll("/+$", "");
        URI endpoint;
        try { endpoint = URI.create(base); } catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid LANGFUSE_BASE_URL"); }
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(String.valueOf(endpoint.getHost()));
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || loopback && "http".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("LANGFUSE_BASE_URL requires HTTPS or loopback HTTP without credentials/query");
        String content = env.getOrDefault("AGENT_TRACE_CONTENT", "metadata");
        if (!Set.of("metadata", "learning").contains(content)) throw new IllegalArgumentException("AGENT_TRACE_CONTENT must be metadata or learning");
        double ratio;
        try { ratio = Double.parseDouble(env.getOrDefault("AGENT_TRACE_SAMPLE_RATIO", "1.0")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid AGENT_TRACE_SAMPLE_RATIO"); }
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) throw new IllegalArgumentException("AGENT_TRACE_SAMPLE_RATIO must be 0..1");
        String auth = Base64.getEncoder().encodeToString((required(env, "LANGFUSE_PUBLIC_KEY") + ":"
                + required(env, "LANGFUSE_SECRET_KEY")).getBytes(StandardCharsets.UTF_8));
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(base + "/api/public/otel/v1/traces")
                .addHeader("Authorization", "Basic " + auth).addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofSeconds(5)).build();
        var processor = BatchSpanProcessor.builder(exporter).setMaxQueueSize(2048).setMaxExportBatchSize(256)
                .setScheduleDelay(Duration.ofSeconds(1)).setExporterTimeout(Duration.ofSeconds(5)).build();
        var provider = SdkTracerProvider.builder().addSpanProcessor(processor)
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder()
                        .put("service.name", "agent-loop-learning").put("service.version", env.getOrDefault("AGENT_RELEASE", "1.0-SNAPSHOT"))
                        .put("deployment.environment.name", "local").build())))
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio))).build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new OtelTelemetryFactory(sdk, new TraceContentPolicy(content.equals("learning"), env), base,
                env.get("LANGFUSE_PROJECT_ID"), env.getOrDefault("AGENT_RELEASE", "1.0-SNAPSHOT"), provider);
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required when tracing is enabled");
        return value;
    }
    public RunTelemetry open(String session, ExecutionRequest request, AgentMode mode, String protocol) {
        return new OtelRunTelemetry(this, tracer, content, session, request, mode, protocol, release);
    }
    public ExternalObserver external() { return external; }
    String url(String traceId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]+")) return null;
        return baseUrl + "/project/" + projectId + "/traces/" + traceId;
    }
    public void close() {
        if (ownedProvider == null) return;
        ownedProvider.forceFlush().join(2, TimeUnit.SECONDS);
        ownedProvider.shutdown().join(3, TimeUnit.SECONDS);
    }
}
