package dev.learning.agent.observability;

import dev.learning.agent.core.*;
import dev.learning.agent.core.react.*;
import dev.learning.agent.core.rewoo.RewooRunner;
import dev.learning.agent.core.planexecute.PlanExecuteRunner;
import dev.learning.agent.core.runtime.*;
import dev.learning.agent.model.*;
import dev.learning.agent.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class OtelTelemetryTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    private OtelTelemetryFactory factory() {
        return new OtelTelemetryFactory(sdk, new TraceContentPolicy(),
                "http://127.0.0.1:3035", "test-project", "test-release");
    }
    @AfterEach void close() { sdk.close(); }
    private static String value(SpanData span, String key) { return span.getAttributes().get(AttributeKey.stringKey(key)); }
    private List<SpanData> type(String type) {
        return exporter.getFinishedSpanItems().stream().filter(s -> type.equals(value(s, "langfuse.observation.type"))).toList();
    }
    private SpanData named(String name) {
        return exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }
    private static AssistantTurn text(String value) {
        return new AssistantTurn(value, List.of(), "stop", null, null,
                new TokenUsage(100L, 20L, 40L, 5L, null, TokenUsage.InputAccounting.INCLUDES_CACHE));
    }
    private static AssistantTurn call() {
        return new AssistantTurn("", List.of(new ToolCall("native-1", "function",
                new ToolCall.FunctionCall("calculator", "{\"expression\":\"1+2\"}"))), "tool_calls");
    }
    private RunResult run(AgentMode mode, AssistantTurn... script) throws Exception {
        Deque<AssistantTurn> answers = new ArrayDeque<>(List.of(script));
        ChatModel model = (request, text, control) -> {
            var answer = answers.removeFirst();
            if (!answer.content().isEmpty()) text.accept(answer.content());
            return answer;
        };
        ToolRegistry tools = new ToolRegistry(); tools.register(new CalculatorTool(new ObjectMapper()));
        AgentRunner runner = switch (mode) {
            case REACT -> new ReActRunner(new DefaultReActEngine(tools));
            case REWOO -> new RewooRunner(tools);
            case PLAN_EXECUTE -> new PlanExecuteRunner(tools, new DefaultReActEngine(tools));
        };
        ExecutionRequest request = new ExecutionRequest(UUID.randomUUID(), "question-sentinel", ModelSettings.defaults());
        var trace = factory().open("safe-session", request, mode, model.protocol());
        var context = RunContexts.create(request.requestId(), mode, model, tools, event -> {}, (k,c,s,r) -> false, trace);
        try (var ignored = trace.activate()) {
            RunResult result = runner.run(request, context);
            trace.completed(result);
            return result;
        } finally { context.control().close(); trace.end(); }
    }
    @Test void threeModesHaveCorrectGenerationToolAndPhaseParents() throws Exception {
        for (AgentMode mode : AgentMode.values()) {
            exporter.reset();
            AssistantTurn[] script = switch (mode) {
                case REACT -> new AssistantTurn[]{call(), text("3")};
                case REWOO -> new AssistantTurn[]{text("{\"version\":1,\"steps\":[{\"id\":\"E1\",\"purpose\":\"sum\",\"tool\":\"calculator\",\"arguments\":{\"expression\":\"1+2\"}}],\"answerEvidence\":[\"E1\"]}"), text("3 [E1]")};
                case PLAN_EXECUTE -> new AssistantTurn[]{text("{\"version\":1,\"revision\":0,\"steps\":[{\"id\":\"S1\",\"goal\":\"sum\",\"successCriteria\":\"numeric result\",\"minEvidence\":1,\"allowedTools\":[\"calculator\"],\"dependsOn\":[]}]}"),
                        call(), text("{\"status\":\"COMPLETE\",\"summary\":\"3\",\"evidenceIds\":[\"E1\"]}"),
                        text("{\"decision\":\"FINISH\",\"reason\":\"done\",\"status\":\"COMPLETE\",\"answerEvidence\":[\"E1\"]}"), text("3 [E1]")};
            };
            var result = run(mode, script);
            assertEquals(ExecutionStatus.COMPLETE, result.status());
            var root = type("agent").get(0);
            assertEquals(result.counters().modelCalls(), type("generation").size());
            assertEquals(1, type("tool").size());
            var tool = type("tool").get(0);
            var toolParent = exporter.getFinishedSpanItems().stream().filter(s -> s.getSpanId().equals(tool.getParentSpanId())).findFirst().orElseThrow();
            assertNotEquals("generation", value(toolParent, "langfuse.observation.type"));
            assertEquals(mode == AgentMode.REACT ? root.getSpanId() : mode == AgentMode.REWOO ? named("agent_loop.work").getSpanId() : named("agent_loop.step").getSpanId(), tool.getParentSpanId());
            for (var span : exporter.getFinishedSpanItems()) {
                assertEquals(root.getTraceId(), span.getTraceId());
                assertEquals("safe-session", value(span, "langfuse.session.id"));
                assertEquals(mode.name(), value(span, "langfuse.observation.metadata.mode"));
                assertTrue(span.hasEnded());
                assertTrue(span.getEndEpochNanos() <= root.getEndEpochNanos());
            }
            var input = new ObjectMapper().readTree(value(root, "langfuse.observation.input"));
            assertEquals("question-sentinel", input.path("questionPreview").asText());
            assertNull(value(root, "agent_loop.content.mode"));
            assertNull(root.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
            assertFalse(Span.current().getSpanContext().isValid());
        }
    }
    @Test void cacheAndReasoningAreNotCountedTwice() throws Exception {
        run(AgentMode.REACT, text("ok"));
        var span = type("generation").get(0);
        assertEquals(100L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        var usage = new ObjectMapper().readTree(value(span, "langfuse.observation.usage_details"));
        assertEquals(60, usage.path("input").asInt());
        assertEquals(15, usage.path("output").asInt());
        assertEquals(120, usage.path("total").asInt());
        assertEquals(40, usage.path("cache_read_input_tokens").asInt());
        assertEquals(5, usage.path("reasoning_output_tokens").asInt());
    }
    @Test void repairIsUnderPlannerAndDoesNotBecomeReplanning() throws Exception {
        run(AgentMode.REWOO, text("invalid JSON"), text("{\"version\":1,\"steps\":[],\"answerEvidence\":[]}"), text("ok"));
        var plan = named("plan agent-loop.rewoo");
        assertEquals(plan.getSpanId(), named("agent_loop.json.repair").getParentSpanId());
        assertTrue(plan.getEvents().stream().anyMatch(e -> e.getName().equals("agent_loop.validation.failed")));
        assertEquals(StatusCode.UNSET, plan.getStatus().getStatusCode());
        assertEquals(3, type("generation").size());
    }
    @Test void bashAndReasoningNeverLeakIntoPreviewsIncludingRoot() {
        var trace = factory().open("session", new ExecutionRequest(UUID.randomUUID(), "ordinary task", ModelSettings.defaults()), AgentMode.REACT, "Chat Completions");
        var request = new ModelRequest(List.of(ChatMessage.user("private history sentinel")), ModelSettings.defaults(), List.of(), OutputMode.TEXT, 64, ModelUse.EXECUTOR);
        try (var active = trace.activate()) {
            try (var model = trace.model(request, new RunScope("REACT", null, 0), 1)) {
                trace.modelFinished(model, new AssistantTurn("COMMAND_SENTINEL", List.of(new ToolCall("native", "function",
                        new ToolCall.FunctionCall("bash", "COMMAND_SENTINEL"))), "tool_calls", "REASONING_SENTINEL"));
            }
            try (var tool = trace.tool("bash", "COMMAND_SENTINEL", "E1", "native", new RunScope("REACT", null, 0))) {
                trace.toolFinished(tool, new ToolResult(ToolStatus.DENIED, "OUTPUT_SENTINEL", "OUTPUT_DENIED", true, false, false));
            }
            trace.completed(new RunResult(ExecutionStatus.PARTIAL, CompletionCheck.UNVERIFIED, "OUTPUT_SENTINEL", null, new RunCounters(1,1,1,0,0)));
        } finally { trace.end(); }
        String serialized = exporter.getFinishedSpanItems().toString();
        for (String sentinel : List.of("COMMAND_SENTINEL", "OUTPUT_SENTINEL", "REASONING_SENTINEL", "private history sentinel")) assertFalse(serialized.contains(sentinel));
        assertTrue(trace.contentRestricted());
    }
    @Test void bashInsideAJsonPlanAlsoRestrictsContentBeforeGenerationEnds() {
        var trace = factory().open("session", new ExecutionRequest(UUID.randomUUID(), "task", ModelSettings.defaults()), AgentMode.REWOO, "Chat Completions");
        var request = new ModelRequest(List.of(ChatMessage.user("task")), ModelSettings.defaults(), List.of(), OutputMode.JSON_OBJECT, 64, ModelUse.CONTROL);
        try (var active = trace.activate(); var span = trace.model(request, new RunScope("PLAN", null, 0), 1)) {
            trace.modelFinished(span, text("{\"tool\":\"bash\",\"command\":\"PLAN_COMMAND_SENTINEL\"}"));
        } finally { trace.end(); }
        assertFalse(exporter.getFinishedSpanItems().toString().contains("PLAN_COMMAND_SENTINEL"));
    }
    @Test void expectedNeedsInputAndDeniedOutcomesAreNotInfrastructureErrors() {
        var trace = factory().open("session", new ExecutionRequest(UUID.randomUUID(), "task", ModelSettings.defaults()), AgentMode.REACT, "Chat Completions");
        try (var active = trace.activate(); var tool = trace.tool("weather_forecast", "{}", "E1", null, new RunScope("REACT", null, 0))) {
            trace.toolFinished(tool, new ToolResult(ToolStatus.NEEDS_INPUT, "candidates", "LOCATION_AMBIGUOUS", true, false, false));
            trace.completed(new RunResult(ExecutionStatus.NEEDS_INPUT, CompletionCheck.UNVERIFIED, "which city?", "NEEDS_INPUT", new RunCounters(0,1,1,0,0)));
        } finally { trace.end(); }
        assertTrue(exporter.getFinishedSpanItems().stream().allMatch(s -> s.getStatus().getStatusCode() == StatusCode.UNSET));
    }
    @Test void concurrentRunsKeepIndependentContextAndIdentifiers() {
        var factory = factory();
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (int i=0;i<2;i++) {
            final String session = "session-" + i;
            futures.add(CompletableFuture.runAsync(() -> {
                var trace = factory.open(session, new ExecutionRequest(UUID.randomUUID(), "task", ModelSettings.defaults()), AgentMode.REACT, "Chat Completions");
                try (var active = trace.activate(); var span = factory.external().operation("test-child")) { span.attribute("test.session", session); }
                finally { trace.end(); }
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (var child : exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("test-child")).toList())
            assertEquals(value(child,"test.session"), value(child,"langfuse.session.id"));
        assertEquals(2, type("agent").stream().map(SpanData::getTraceId).distinct().count());
    }
    @Test void previewsKeepContentUnmodifiedAndPreserveValidBoundedUnicode() {
        var policy = new TraceContentPolicy();
        assertEquals("secret-sentinel", policy.preview("secret-sentinel", false));
        assertEquals("Bearer token-sentinel", policy.preview("Bearer token-sentinel", false));
        String preview = policy.preview("天气🌤".repeat(3000),false);
        assertTrue(preview.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096);
        assertTrue(preview.endsWith("[truncated]"));
        assertNull(policy.preview("anything",true));
    }
    @Test void missingTokenSubsetsRemainMissingRatherThanInventedZeros() throws Exception {
        run(AgentMode.REACT,new AssistantTurn("ok",List.of(),"stop",null,null,
                new TokenUsage(100L,20L,null,null,null,TokenUsage.InputAccounting.INCLUDES_CACHE)));
        var usage=new ObjectMapper().readTree(value(type("generation").get(0),"langfuse.observation.usage_details"));
        assertEquals(120,usage.path("total").asInt());
        assertFalse(usage.has("cache_read_input_tokens"));
        assertFalse(usage.has("cache_creation_input_tokens"));
        assertFalse(usage.has("reasoning_output_tokens"));
    }
    @Test void escapedControlCharactersCannotExpandAnUnboundedPreview() throws Exception {
        var policy=new TraceContentPolicy();
        String preview=policy.preview("\u0000\"\\".repeat(3000),false);
        String encoded=new ObjectMapper().writeValueAsString(preview);
        assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);
        assertEquals(preview,new ObjectMapper().readValue(encoded,String.class));
    }
    @Test void usageAlreadyReportedSurvivesAFailedStreamAndRemainsMarkedIncomplete() {
        var trace=factory().open("session",new ExecutionRequest(UUID.randomUUID(),"task",ModelSettings.defaults()),AgentMode.REACT,"Chat Completions");
        var request=new ModelRequest(List.of(ChatMessage.user("task")),ModelSettings.defaults(),List.of(),OutputMode.TEXT,64,ModelUse.EXECUTOR);
        try(var active=trace.activate();var span=trace.model(request,new RunScope("REACT",null,0),1)) {
            trace.modelUsage(span,new TokenUsage(100L,0L,40L,null,null,TokenUsage.InputAccounting.INCLUDES_CACHE),false);
            span.fail(new java.io.IOException("sensitive error not exported"));
        } finally {trace.end();}
        var generation=type("generation").get(0);
        assertEquals(100L,generation.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(false,generation.getAttributes().get(AttributeKey.booleanKey("agent_loop.model.usage_complete")));
        assertEquals(StatusCode.ERROR,generation.getStatus().getStatusCode());
        assertFalse(generation.getAttributes().toString().contains("sensitive error"));
    }
}
