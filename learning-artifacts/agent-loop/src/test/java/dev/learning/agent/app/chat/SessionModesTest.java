package dev.learning.agent.app.chat;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ToolCall;
import dev.learning.agent.tool.ToolStatus;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherProvider;
import dev.learning.agent.tool.weather.WeatherService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionModesTest {
    @Test
    void allSixModeChangesReceiveThePreviousQuestionAndAnswer() throws Exception {
        for (AgentMode first : AgentMode.values()) {
            for (AgentMode next : AgentMode.values()) {
                if (first == next) {
                    continue;
                }
                ScriptedModel model = new ScriptedModel();
                var session = model.sessions().create();
                model.answer(first, "previous-answer");
                assertEquals(ExecutionStatus.COMPLETE, run(session, first, "previous-question").status());
                int nextRequest = model.requests.size();
                model.answer(next, "next-answer");
                assertEquals(ExecutionStatus.COMPLETE, run(session, next, "follow-up").status());
                String input = model.input(nextRequest);
                assertTrue(input.contains("previous-question"), first + " -> " + next);
                assertTrue(input.contains("previous-answer"), first + " -> " + next);
                assertTrue(input.contains("follow-up"));
                if (next == AgentMode.REACT) {
                    model.requests.get(nextRequest).messages().stream().filter(m -> "assistant".equals(m.role()))
                        .forEach(m -> assertNull(m.toolCalls(),
                            "shared answers are plain messages, not empty native calls"));
                }
                assertEquals(next, session.status().mode());
                assertFalse(session.status().running());
            }
        }
    }

    @Test
    void switchingBackRebuildsContextIncludingInterveningModes() throws Exception {
        ScriptedModel model = new ScriptedModel();
        var session = model.sessions().create();
        model.answer(AgentMode.REACT, "react-answer");
        run(session, AgentMode.REACT, "first-question");
        model.answer(AgentMode.REWOO, "rewoo-answer");
        run(session, AgentMode.REWOO, "second-question");
        model.answer(AgentMode.PLAN_EXECUTE, "plan-answer");
        run(session, AgentMode.PLAN_EXECUTE, "third-question");
        int finalRequest = model.requests.size();
        model.answer(AgentMode.REACT, "combined-answer");
        run(session, AgentMode.REACT, "combine");
        String input = model.input(finalRequest);
        for (String answer : List.of("react-answer", "rewoo-answer", "plan-answer")) {
            assertTrue(input.contains(answer));
        }
    }

    @Test
    void nativeTranscriptStaysWithinConsecutiveReactRuns() throws Exception {
        ScriptedModel model = new ScriptedModel();
        var session = model.sessions().create();
        model.outputs.add(new AssistantTurn("", List.of(new ToolCall("native-old", "function",
            new ToolCall.FunctionCall("calculator", "{\"expression\":\"120+18\"}"))), "tool_calls"));
        model.outputs.add(text("138"));
        run(session, AgentMode.REACT, "calculate");
        int consecutiveRequest = model.requests.size();
        model.answer(AgentMode.REACT, "the result was 138");
        run(session, AgentMode.REACT, "repeat the result");
        assertTrue(model.requests.get(consecutiveRequest).messages().stream().anyMatch(m -> "tool".equals(m.role())));
        int switchedRequest = model.requests.size();
        model.answer(AgentMode.REWOO, "remembered 138");
        run(session, AgentMode.REWOO, "recall");
        String input = model.input(switchedRequest);
        assertTrue(input.contains("138"));
        assertTrue(input.contains("calculator"));
        assertTrue(input.contains("SUCCESS"));
        assertFalse(input.contains("native-old"));
        assertTrue(model.requests.get(switchedRequest).messages().stream().noneMatch(m -> "tool".equals(m.role())));
    }

    @Test
    void failureAfterAToolAttemptRemainsVisibleWhenChangingMode() throws Exception {
        ScriptedModel model = new ScriptedModel();
        var session = model.sessions().create();
        model.outputs.add(new AssistantTurn("",
            List.of(
                new ToolCall("bad", "function", new ToolCall.FunctionCall("calculator", "{\"expression\":\"1/0\"}"))),
            "tool_calls"));
        // The empty queue makes the following model request fail after calculator has run.
        assertThrows(IOException.class, () -> run(session, AgentMode.REACT, "failed-question"));
        int nextRequest = model.requests.size();
        model.answer(AgentMode.PLAN_EXECUTE, "recovered");
        run(session, AgentMode.PLAN_EXECUTE, "continue safely");
        String input = model.input(nextRequest);
        assertTrue(input.contains("failed-question"));
        assertTrue(input.contains("FAILED"));
        assertTrue(input.contains("calculator"));
        assertTrue(input.contains(ToolStatus.ERROR.name()));
        assertTrue(input.contains("\"executed\":true"));
    }

    @Test
    void clarificationAndCandidatesSurviveAModeChange() throws Exception {
        ScriptedModel model = new ScriptedModel();
        WeatherProvider provider = mock(WeatherProvider.class);
        when(provider.sources()).thenReturn(List.of());
        when(provider.search(any(), anyString(), any())).thenReturn(
            List.of(new GeoLocation(1, "Paris", "FR", "France", "Ile-de-France", 48.86, 2.35, "Europe/Paris"),
                new GeoLocation(2, "Paris", "US", "United States", "Texas", 33.66, -95.55, "America/Chicago")));
        var weather = new WeatherService(provider, java.time.Clock.systemUTC());
        var session = new AgentSessions(model, Path.of("."), null, weather).create();
        model.outputs.add(new AssistantTurn("", List.of(
            new ToolCall("pending", "function", new ToolCall.FunctionCall("current_weather", "{\"city\":\"Paris\"}"))),
            "tool_calls"));
        model.outputs.add(text("请选择 Paris, France 或 Paris, Texas"));
        assertEquals(ExecutionStatus.NEEDS_INPUT, run(session, AgentMode.REACT, "weather").status());
        verify(provider, never()).current(any(), any());
        int nextRequest = model.requests.size();
        model.answer(AgentMode.REWOO, "France selected");
        run(session, AgentMode.REWOO, "France");
        assertTrue(model.input(nextRequest).contains("Paris, France"));
        assertTrue(model.input(nextRequest).contains("NEEDS_INPUT"));
    }

    @Test
    void historyIsBoundedAndDoesNotCrossSessions() throws Exception {
        ScriptedModel model = new ScriptedModel();
        var sessions = model.sessions();
        var session = sessions.create();
        for (int i = 0; i < 6; i++) {
            model.answer(AgentMode.REACT, "answer-" + i);
            run(session, AgentMode.REACT, "question-" + i);
        }
        int switched = model.requests.size();
        model.answer(AgentMode.REWOO, "bounded");
        run(session, AgentMode.REWOO, "next");
        assertFalse(model.input(switched).contains("question-0"));
        assertTrue(model.input(switched).contains("question-5"));
        int fresh = model.requests.size();
        model.answer(AgentMode.PLAN_EXECUTE, "fresh");
        run(sessions.create(), AgentMode.PLAN_EXECUTE, "new conversation");
        assertFalse(model.input(fresh).contains("question-5"));
    }

    private static RunResult run(AgentSessions.Session session, AgentMode mode, String question) throws Exception {
        return session.execute(session.accept(question, ModelSettings.defaults(), mode), (type, data) -> {
        });
    }

    private static AssistantTurn text(String value) {
        return new AssistantTurn(value, List.of(), "stop");
    }

    private static final class ScriptedModel implements ChatModel {
        private final Deque<AssistantTurn> outputs = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        public AssistantTurn stream(ModelRequest request, java.util.function.Consumer<String> onText,
            dev.learning.agent.core.runtime.RunControl control) throws IOException {
            requests.add(request);
            if (outputs.isEmpty()) {
                throw new IOException("controlled model failure");
            }
            AssistantTurn answer = outputs.removeFirst();
            if (!answer.content().isEmpty()) {
                onText.accept(answer.content());
            }
            return answer;
        }

        private void answer(AgentMode mode, String answer) {
            if (mode == AgentMode.REWOO) {
                outputs.add(text("{\"version\":1,\"steps\":[],\"answerEvidence\":[]}"));
            }
            if (mode == AgentMode.PLAN_EXECUTE) {
                outputs.add(text("{\"version\":1,\"revision\":0,\"steps\":[]}"));
            }
            outputs.add(text(answer));
        }

        private AgentSessions sessions() {
            return new AgentSessions(this, Path.of("."), null);
        }

        private String input(int index) {
            return requests.get(index).messages().stream().map(m -> m.content() == null ? "" : m.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
