package dev.learning.agent.tool.weather;

import dev.learning.agent.core.*;
import dev.learning.agent.core.react.*;
import dev.learning.agent.core.rewoo.*;
import dev.learning.agent.core.planexecute.*;
import dev.learning.agent.core.runtime.*;
import dev.learning.agent.model.*;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WeatherModesTest {
    private static final String INPUT = "{\"city\":\"北京\",\"country_code\":\"CN\",\"admin1\":\"北京市\",\"days\":2}";
    private static AssistantTurn text(String content) { return new AssistantTurn(content, List.of(), "stop"); }
    private static AssistantTurn call() { return new AssistantTurn("", List.of(new ToolCall("call-weather", "function", new ToolCall.FunctionCall("weather_forecast", INPUT))), "tool_calls"); }
    private static AssistantTurn plan() { return text("{\"version\":1,\"steps\":[{\"id\":\"E1\",\"purpose\":\"forecast\",\"tool\":\"weather_forecast\",\"arguments\":" + INPUT + "}],\"answerEvidence\":[\"E1\"]}"); }
    private static AssistantTurn goals() { return text("{\"version\":1,\"revision\":0,\"steps\":[{\"id\":\"S1\",\"goal\":\"forecast\",\"successCriteria\":\"two dated days\",\"minEvidence\":1,\"allowedTools\":[\"weather_forecast\"],\"dependsOn\":[]}]}"); }
    private static AssistantTurn step() { return text("{\"status\":\"COMPLETE\",\"summary\":\"forecast obtained\",\"evidenceIds\":[\"E1\"]}"); }
    private static AssistantTurn finish() { return text("{\"decision\":\"FINISH\",\"reason\":\"done\",\"status\":\"COMPLETE\",\"answerEvidence\":[\"E1\"]}"); }
    private AgentRunner runner(AgentMode mode, ToolRegistry tools) {
        return switch (mode) { case REACT -> new ReActRunner(new DefaultReActEngine(tools));
            case REWOO -> new RewooRunner(tools); case PLAN_EXECUTE -> new PlanExecuteRunner(tools, new DefaultReActEngine(tools)); };
    }
    @ParameterizedTest @EnumSource(AgentMode.class)
    void everyModeCanDiscoverDecodeAndExecuteForecast(AgentMode mode) throws Exception {
        var provider = new WeatherToolTest.Provider(); var tools = tools(provider);
        Deque<AssistantTurn> script = new ArrayDeque<>(switch (mode) {
            case REACT -> List.of(call(), text("两天预报 [E1]"));
            case REWOO -> List.of(plan(), text("两天预报 [E1]"));
            case PLAN_EXECUTE -> List.of(goals(), call(), step(), finish(), text("两天预报 [E1]"));
        });
        RunContext context = context(mode, tools, script, new ArrayList<>());
        try {
            RunResult result = runner(mode, tools).run(new ExecutionRequest(context.requestId(), "北京未来两天天气", ModelSettings.defaults()), context);
            assertEquals(ExecutionStatus.COMPLETE, result.status()); assertEquals(1, result.counters().toolsExecuted());
            assertEquals(2, context.evidence().requireUsable("E1").result().sources().size());
            assertEquals(2, StrictJson.object(context.evidence().requireUsable("E1").result().content()).at("/forecast/days").size());
            assertTrue(script.isEmpty());
        } finally { context.control().close(); }
    }
    @ParameterizedTest @EnumSource(AgentMode.class)
    void clarificationStopsToolsAndNextTurnRetainsQuestion(AgentMode mode) throws Exception {
        var provider = new WeatherToolTest.Provider();
        // Same province labels here force a genuine unresolved candidate, even with the supplied admin1.
        provider.places = List.of(WeatherToolTest.BEIJING, new WeatherModels.GeoLocation(2, "北京", "CN", "中国", "北京市", 40, 116, "Asia/Shanghai"));
        var tools = tools(provider); var requests = new ArrayList<ModelRequest>();
        Deque<AssistantTurn> script = new ArrayDeque<>(switch (mode) {
            case REACT -> List.of(call(), text("请确认具体地点。"));
            case REWOO -> List.of(plan(), text("请确认具体地点。"));
            case PLAN_EXECUTE -> List.of(goals(), call(), text("请确认具体地点。"));
        });
        AgentRunner runner = runner(mode, tools);
        RunContext first = context(mode, tools, script, requests);
        try {
            RunResult result = runner.run(new ExecutionRequest(first.requestId(), "北京未来七天天气", ModelSettings.defaults()), first);
            assertEquals(ExecutionStatus.NEEDS_INPUT, result.status()); assertEquals(1, result.counters().toolAttempts());
            assertEquals(0, provider.weatherCalls); assertFalse(first.evidence().snapshot().get(0).result().hasValue());
            assertTrue(requests.get(requests.size() - 1).tools().isEmpty()); assertTrue(script.isEmpty());
        } finally { first.control().close(); }
        provider.places = List.of(WeatherToolTest.BEIJING);
        script.addAll(switch (mode) {
            case REACT -> List.of(call(), text("已查询 [E1]"));
            case REWOO -> List.of(plan(), text("已查询 [E1]"));
            case PLAN_EXECUTE -> List.of(goals(), call(), step(), finish(), text("已查询 [E1]"));
        });
        int start = requests.size();
        RunContext second = context(mode, tools, script, requests);
        try {
            assertEquals(ExecutionStatus.COMPLETE, runner.run(new ExecutionRequest(second.requestId(), "我指北京市", ModelSettings.defaults()), second).status());
            assertTrue(StrictJson.write(requests.get(start).messages()).contains("北京未来七天天气"));
            assertEquals(1, second.budget().snapshot().toolsExecuted());
        } finally { second.control().close(); }
    }
    @org.junit.jupiter.api.Test
    void bindingPreservesIntegersAndChecksReferencesWithoutToolSpecificBranches() throws Exception {
        var tools = tools(new WeatherToolTest.Provider()); var evidence = new InMemoryEvidenceStore();
        evidence.put(new Evidence("E1", null, "other", "{}", new ToolResult(ToolStatus.SUCCESS, "北京", null, true, true, false), 0));
        var input = new ForecastWeatherInput("#E1", "CN", null, 7);
        ToolInput bound = EvidenceBinder.bind(input, evidence, tools);
        assertEquals(7, ((ForecastWeatherInput) bound).days()); assertEquals("北京", ((ForecastWeatherInput) bound).city());
        assertEquals(Set.of("E1"), EvidenceBinder.references(input));
    }

    @org.junit.jupiter.api.Test
    void pendingInputSkipsRemainingNativeCallsAndFallbackNeedsNoModelSlot() throws Exception {
        var provider = new WeatherToolTest.Provider();
        provider.places = List.of(WeatherToolTest.BEIJING, new WeatherModels.GeoLocation(2, "北京", "CN", "中国", "北京市", 40, 116, "Asia/Shanghai"));
        var tools = tools(provider);
        AssistantTurn batch = new AssistantTurn("", List.of(call().calls().get(0),
                new ToolCall("later", "function", new ToolCall.FunctionCall("weather_forecast", INPUT))), "tool_calls");
        var script = new ArrayDeque<>(List.of(batch));
        RunContext context = context(AgentMode.REACT, tools, script, new ArrayList<>());
        try {
            var runner = new ReActRunner(new DefaultReActEngine(tools), 1);
            assertEquals(ExecutionStatus.NEEDS_INPUT, runner.run(new ExecutionRequest(context.requestId(), "查询地点", ModelSettings.defaults()), context).status());
            assertEquals(1, context.budget().snapshot().toolAttempts());
            assertEquals(ToolStatus.SKIPPED, context.evidence().snapshot().get(1).result().status());
            assertTrue(script.isEmpty());
        } finally { context.control().close(); }
    }

    @org.junit.jupiter.api.Test
    void invalidParametersDoNotExecuteAndTruncationRetainsSourceMetadata() throws Exception {
        var provider = new WeatherToolTest.Provider(); var tools = tools(provider);
        var script = new ArrayDeque<AssistantTurn>();
        RunContext context = context(AgentMode.REACT, tools, script, new ArrayList<>());
        try {
            var call = new ToolCall("bad", "function", new ToolCall.FunctionCall("weather_forecast", "{\"city\":\"北京\",\"days\":\"7\"}"));
            Evidence failed = context.tools().invokeNative(call, "E1", tools.names(), new RunScope("TEST", null, 0), context);
            assertEquals("INVALID_ARGUMENTS", failed.result().errorCode()); assertFalse(failed.result().executed());
            assertTrue(provider.searches.isEmpty());
            // Fill the shared evidence allowance, then ensure attribution survives truncation.
            context.evidence().put(new Evidence("E2", null, "fixture", "{}", new ToolResult(ToolStatus.SUCCESS, "x".repeat(31990), null, false, true, false), 0));
            var input = tools.decode("weather_forecast", INPUT);
            Evidence weather = context.tools().invoke(input, "E3", tools.names(), new RunScope("TEST", null, 0), context);
            assertTrue(weather.result().truncated()); assertEquals(2, weather.result().sources().size());
            assertTrue(DefaultToolExecutionService.modelContent(weather).contains("https://open-meteo.com/"));
        } finally { context.control().close(); }
    }

    private ToolRegistry tools(WeatherToolTest.Provider provider) {
        var tools = new ToolRegistry(); var service = new WeatherService(provider, WeatherToolTest.CLOCK);
        tools.register(new CurrentWeatherTool(service)); tools.register(new WeatherForecastTool(service)); return tools;
    }
    private RunContext context(AgentMode mode, ToolRegistry tools, Deque<AssistantTurn> script, List<ModelRequest> requests) {
        ChatModel model = (request, text, control) -> { requests.add(request); AssistantTurn answer = script.removeFirst();
            if (request.use() == ModelUse.FINALIZER || request.tools().isEmpty()) text.accept(answer.content()); return answer; };
        return RunContexts.create(UUID.randomUUID(), mode, model, tools, ignored -> {}, (k,c,s,r) -> false);
    }
}
