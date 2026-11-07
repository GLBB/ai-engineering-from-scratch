package dev.learning.agent.app.chat;

import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.CompletionCheck;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ModelSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    private final AgentSessions sessions = new AgentSessions((request, text, control) -> {
        assertEquals(new ModelSettings("deepseek-v4-pro", "max"), request.settings());
        text.accept("hello"); return new AssistantTurn("hello", List.of(), "stop");
    }, Path.of("."), null);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(sessions, new ObjectMapper()))
            .setControllerAdvice(new ChatErrors()).build();
    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.with(r -> { r.setServerName("127.0.0.1"); r.setServerPort(8080); return r; })
                .header("Origin", "http://127.0.0.1:8080");
    }
    private MockHttpServletRequestBuilder chat(String session, String body) {
        return local(post("/api/chat")).header("X-Agent-Session", session).contentType("application/json").content(body);
    }
    @Test void chatStreamsRequestIdentityAndResultBeforeDone() throws Exception {
        AgentSessions.Session session = sessions.create();
        MvcResult started = mvc.perform(chat(session.id(), "{\"prompt\":\"hi\",\"model\":\"deepseek-v4-pro\",\"reasoningLevel\":\"max\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        String output = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(output.contains("\"type\":\"text\",\"data\":\"hello\""));
        assertTrue(output.contains("\"type\":\"run_started\""));
        assertTrue(output.indexOf("\"type\":\"run_result\"") < output.indexOf("\"type\":\"done\""));
        for (String line : output.split("\n")) assertFalse(new ObjectMapper().readTree(line).path("requestId").asText().isBlank());
        assertFalse(session.status().running()); assertEquals(AgentMode.REACT, session.status().mode());
    }
    @Test void invalidRequestsDoNotChangeModeAndHaveJsonErrors() throws Exception {
        AgentSessions.Session session = sessions.create();
        for (String body : List.of("{\"prompt\":\"\"}", "{\"prompt\":\"hi\",\"mode\":\"UNKNOWN\"}", "{\"prompt\":\"hi\",\"model\":\"unknown\"}")) {
            mvc.perform(chat(session.id(), body)).andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("INVALID_REQUEST")));
            assertNull(session.status().mode());
        }
    }
    @Test void statusDoesNotCreateMissingConversation() throws Exception {
        mvc.perform(local(get("/api/session/status")).header("X-Agent-Session", "missing"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(containsString("SESSION_NOT_FOUND")));
        AgentSessions.Session session = sessions.create();
        mvc.perform(local(get("/api/session/status")).header("X-Agent-Session", session.id()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"running\":false")));
    }
    @Test void concurrentRequestIsRejectedButModeCanChangeAfterCleanup() throws Exception {
        AgentSessions.Session session = sessions.create();
        RunLease lease = session.accept("hi", ModelSettings.defaults(), AgentMode.REWOO);
        mvc.perform(chat(session.id(), "{\"prompt\":\"hi\",\"mode\":\"REACT\"}"))
                .andExpect(status().isConflict()).andExpect(content().string(containsString("RUN_IN_PROGRESS")));
        lease.cancel("test accepted cancellation");
        MvcResult started = mvc.perform(chat(session.id(), "{\"prompt\":\"hi\",\"mode\":\"REACT\",\"model\":\"deepseek-v4-pro\",\"reasoningLevel\":\"max\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        String output = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(output.contains("\"mode\":\"REACT\""));
        assertFalse(output.contains("modeLocked"));
        assertFalse(session.status().running());
    }
    @Test void needsInputEndsNormallyWithoutAnErrorEvent() throws Exception {
        var mockedSessions = org.mockito.Mockito.mock(AgentSessions.class);
        var mockedSession = org.mockito.Mockito.mock(AgentSessions.Session.class);
        var tools = new dev.learning.agent.tool.ToolRegistry();
        var lease = new RunLease(new ExecutionRequest(java.util.UUID.randomUUID(), "hi", ModelSettings.defaults()),
                AgentMode.REACT, (r,c) -> null, (r,t,c) -> new AssistantTurn("", List.of(), "stop"), tools, ignored -> {});
        org.mockito.Mockito.when(mockedSessions.get("pending")).thenReturn(mockedSession);
        org.mockito.Mockito.when(mockedSession.accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(lease);
        org.mockito.Mockito.when(mockedSession.execute(org.mockito.ArgumentMatchers.eq(lease), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RunResult(ExecutionStatus.NEEDS_INPUT, CompletionCheck.UNVERIFIED, "请确认地点", "NEEDS_INPUT", lease.context().budget().snapshot()));
        MockMvc pendingMvc = MockMvcBuilders.standaloneSetup(new ChatController(mockedSessions, new ObjectMapper())).build();
        try {
            MvcResult started = pendingMvc.perform(chat("pending", "{\"prompt\":\"hi\"}")).andExpect(request().asyncStarted()).andReturn();
            String output = pendingMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(output.contains("NEEDS_INPUT")); assertTrue(output.contains("\"type\":\"done\""));
            assertFalse(output.contains("\"type\":\"error\""));
        } finally { lease.cancel("test cleanup"); }
    }

    @Test void foreignOriginCannotApproveBash() throws Exception {
        mvc.perform(post("/api/approve").header("Origin", "http://other.example").contentType("application/json")
                .content("{\"id\":\"approval\",\"approved\":true}"))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("FORBIDDEN_ORIGIN")));
    }
}
