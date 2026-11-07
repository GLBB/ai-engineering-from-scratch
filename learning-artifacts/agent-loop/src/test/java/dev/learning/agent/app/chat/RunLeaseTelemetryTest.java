package dev.learning.agent.app.chat;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.*;
import dev.learning.agent.observability.*;
import dev.learning.agent.tool.weather.WeatherService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RunLeaseTelemetryTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
    private final OtelTelemetryFactory factory = new OtelTelemetryFactory(sdk, new TraceContentPolicy(false, Map.of()),
            "http://127.0.0.1:3035", "test-project", "test");
    @AfterEach void close() { sdk.close(); }
    private AgentSessions.Session session(ChatModel model) {
        return new AgentSessions(model, Path.of("."), null, mock(WeatherService.class), factory).create();
    }
    private List<SpanData> roots() { return exporter.getFinishedSpanItems().stream().filter(s -> !s.getParentSpanContext().isValid()).toList(); }
    private static String value(SpanData span, String key) { return span.getAttributes().get(AttributeKey.stringKey(key)); }
    @Test void acceptedCancellationEndsExactlyOnceWithoutModelOrAccessCredential() throws Exception {
        var session = session((r,t,c) -> { fail("not started"); return null; });
        var lease = session.accept("before start", ModelSettings.defaults(), AgentMode.REACT);
        assertTrue(roots().isEmpty());
        lease.cancel("closed"); lease.finish(); lease.cancel("late callback");
        assertNull(session.execute(lease,(t,d)->{}));
        assertEquals(1, roots().size());
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals("CANCELLED",value(roots().get(0),"agent_loop.run.status"));
        assertNotEquals(session.id(),value(roots().get(0),"langfuse.session.id"));
        assertFalse(exporter.getFinishedSpanItems().toString().contains(session.id()));
        assertFalse(session.status().running());
    }
    @Test void modesHaveSeparateTracesAndOneObservationSessionWithBrowserLinks() throws Exception {
        Deque<String> script = new ArrayDeque<>(List.of("hello","{\"version\":1,\"steps\":[],\"answerEvidence\":[]}","follow-up"));
        var session=session((r,t,c)->new AssistantTurn(script.removeFirst(),List.of(),"stop"));
        var started=new ArrayList<RunLease.Started>();
        for (var mode:List.of(AgentMode.REACT,AgentMode.REWOO)) {
            var lease=session.accept("question",ModelSettings.defaults(),mode);
            session.execute(lease,(type,data)->{if(type.equals("run_started"))started.add((RunLease.Started)data);});
        }
        assertEquals(2, roots().size());
        assertNotEquals(roots().get(0).getTraceId(), roots().get(1).getTraceId());
        assertEquals(value(roots().get(0),"langfuse.session.id"),value(roots().get(1),"langfuse.session.id"));
        assertNotEquals(value(roots().get(0),"agent_loop.mode"),value(roots().get(1),"agent_loop.mode"));
        assertTrue(started.stream().allMatch(s->s.traceId().length()==32&&s.traceUrl().endsWith(s.traceId())));
    }
    @Test void runningCancellationKeepsRootOpenUntilWorkerCleansUp() throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var session=session((r,t,c)->{entered.countDown();assertTrue(finish.await(3,TimeUnit.SECONDS));c.check();return null;});
        var lease=session.accept("cancel",ModelSettings.defaults(),AgentMode.REACT);
        var worker=CompletableFuture.runAsync(()->assertThrows(InterruptedException.class,()->session.execute(lease,(t,d)->{})));
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        lease.cancel("disconnect");
        assertTrue(roots().isEmpty());assertTrue(session.status().running());
        finish.countDown();worker.get(3,TimeUnit.SECONDS);
        assertEquals(1,roots().size());
        assertEquals("CANCELLED",value(roots().get(0),"agent_loop.run.status"));
        assertNotNull(value(roots().get(0),"langfuse.observation.output"));
        assertFalse(session.status().running());
    }
}
