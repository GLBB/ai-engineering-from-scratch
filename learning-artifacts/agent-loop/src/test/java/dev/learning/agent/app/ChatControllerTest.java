package dev.learning.agent.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.core.TraceEvent;
import dev.learning.agent.deepseek.model.ModelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {
    @Test
    void chatStreamsTextAndCompletionAsSeparateEvents() throws Exception {
        AgentSessions sessions = mock(AgentSessions.class);
        AgentSessions.Session session = mock(AgentSessions.Session.class);
        when(sessions.get("test-session")).thenReturn(session);
        when(session.begin()).thenReturn(true);
        doAnswer(call -> {
            ModelSettings settings = call.getArgument(1);
            assertEquals(new ModelSettings("deepseek-v4-pro", "max"), settings);
            EventSink sink = call.getArgument(2);
            sink.emit("trace", TraceEvent.user("user-1", "hi", 123));
            sink.emit("text", "hello");
            return null;
        }).when(session).run(eq("hi"), any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(sessions, new ObjectMapper())).build();

        MvcResult started = mvc.perform(post("/api/chat")
                .with(request -> {
                    request.setServerName("127.0.0.1");
                    request.setServerPort(8080);
                    return request;
                })
                .header("Origin", "http://127.0.0.1:8080")
                .header("X-Agent-Session", "test-session")
                .contentType("application/json")
                .content("{\"prompt\":\"hi\",\"model\":\"deepseek-v4-pro\","
                        + "\"reasoningLevel\":\"max\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"trace\"")))
                .andExpect(content().string(containsString("\"kind\":\"user\"")))
                .andExpect(content().string(containsString("{\"type\":\"text\",\"data\":\"hello\"}\n")))
                .andExpect(content().string(containsString("{\"type\":\"done\",\"data\":\"\"}\n")));
    }

    @Test
    void emptyPromptGetsBadRequestInsteadOfStartingAgent() throws Exception {
        AgentSessions sessions = mock(AgentSessions.class);
        AgentSessions.Session session = mock(AgentSessions.Session.class);
        when(sessions.get("test-session")).thenReturn(session);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(sessions, new ObjectMapper())).build();

        mvc.perform(post("/api/chat")
                .with(request -> {
                    request.setServerName("127.0.0.1");
                    request.setServerPort(8080);
                    return request;
                })
                .header("Origin", "http://127.0.0.1:8080")
                .header("X-Agent-Session", "test-session")
                .contentType("application/json")
                .content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedModelGetsBadRequest() throws Exception {
        AgentSessions sessions = mock(AgentSessions.class);
        when(sessions.get("test-session")).thenReturn(mock(AgentSessions.Session.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(sessions, new ObjectMapper())).build();

        mvc.perform(post("/api/chat")
                .with(request -> {
                    request.setServerName("127.0.0.1");
                    request.setServerPort(8080);
                    return request;
                })
                .header("Origin", "http://127.0.0.1:8080")
                .header("X-Agent-Session", "test-session")
                .contentType("application/json")
                .content("{\"prompt\":\"hi\",\"model\":\"unknown\","
                        + "\"reasoningLevel\":\"max\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void foreignOriginCannotApproveBash() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ChatController(mock(AgentSessions.class), new ObjectMapper())).build();

        mvc.perform(post("/api/approve")
                .header("Origin", "http://other.example")
                .header("X-Agent-Session", "test-session")
                .contentType("application/json")
                .content("{\"id\":\"approval\",\"approved\":true}"))
                .andExpect(status().isForbidden());
    }
}
