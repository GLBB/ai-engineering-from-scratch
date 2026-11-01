package dev.learning.agent.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.app.LocalRequest;
import dev.learning.agent.core.*;
import org.springframework.web.context.request.async.WebAsyncUtils;
import dev.learning.agent.deepseek.model.ModelSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Browser API for chat streaming and two explicit Bash approvals. */
@RestController
final class ChatController {
    private static final String SESSION_HEADER = "X-Agent-Session";
    private final AgentSessions sessions;
    private final ObjectMapper json;

    ChatController(AgentSessions sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @GetMapping("/api/session")
    ResponseEntity<?> session(HttpServletRequest request) {
        if (!LocalRequest.isLocalHost(request)) {
            throw new ApiFailure(403, "FORBIDDEN_ORIGIN", "Only local same-origin requests are allowed.");
        }
        AgentSessions.Session session = sessions.create();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(session.status());
    }

    @GetMapping("/api/session/status")
    ResponseEntity<?> status(HttpServletRequest request) {
        if (!LocalRequest.isLocalHost(request)) throw new ApiFailure(403, "FORBIDDEN_ORIGIN", "Only local requests are allowed.");
        AgentSessions.Session session = existing(request);
        if (session == null) throw new ApiFailure(401, "SESSION_NOT_FOUND", "Create a new conversation.");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(session.status());
    }

    @PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> chat(@RequestBody Prompt body, HttpServletRequest request) {
        if (!LocalRequest.hasSameOrigin(request)) {
            throw new ApiFailure(403, "FORBIDDEN_ORIGIN", "Only local same-origin requests are allowed.");
        }
        AgentSessions.Session session = existing(request);
        if (session == null) {
            throw new ApiFailure(401, "SESSION_NOT_FOUND", "Create a new conversation.");
        }
        boolean invalidPrompt = body.prompt() == null || body.prompt().isBlank()
                || body.prompt().length() > 10_000;
        if (invalidPrompt) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Invalid prompt, model or reasoning level.");
        }
        ModelSettings settings;
        try {
            String model = body.model() == null ? ModelSettings.DEFAULT_MODEL : body.model();
            String level = body.reasoningLevel() == null ? "off" : body.reasoningLevel();
            settings = new ModelSettings(model, level);
        } catch (IllegalArgumentException e) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Invalid prompt, model or reasoning level.");
        }
        RunLease lease = session.accept(body.prompt(), settings, body.mode());
        WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(lease.requestId(), new AgentRunInterceptor(lease));

        StreamingResponseBody stream = output -> {
            EventSink sink = (type, data) -> {
                try {
                    byte[] line = (json.writeValueAsString(new ChatEvent(lease.requestId(), type, data)) + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    output.write(line);
                    output.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            try {
                RunResult result = session.execute(lease, sink);
                if (result == null) return;
                sink.emit("run_result", result);
                if (result.status() == ExecutionStatus.COMPLETE || result.status() == ExecutionStatus.PARTIAL) sink.emit("done", "");
                else sink.emit("error", new ChatErrors.ApiError(result.errorCode() == null ? "INCOMPLETE_RUN" : result.errorCode(), "Run did not complete."));
            } catch (Exception e) {
                lease.cancel("stream or execution failed");
                String message = e.getMessage() == null ? "Agent failed." : e.getMessage();
                try {
                    ExecutionStatus status = (e instanceof InterruptedException || lease.context().control().cancelled()) ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED;
                    sink.emit("run_result", new RunResult(status, CompletionCheck.UNVERIFIED, "", "EXECUTION_ERROR", lease.context().budget().snapshot()));
                    sink.emit("error", new ChatErrors.ApiError("EXECUTION_ERROR", message));
                } catch (UncheckedIOException ignored) {
                    // The browser has already disconnected.
                }
            }
        };
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(stream);
    }

    @PostMapping(path = "/api/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> approve(@RequestBody Decision body, HttpServletRequest request) {
        if (!LocalRequest.hasSameOrigin(request)) {
            throw new ApiFailure(403, "FORBIDDEN_ORIGIN", "Only local same-origin requests are allowed.");
        }
        AgentSessions.Session session = existing(request);
        if (session == null) {
            throw new ApiFailure(401, "SESSION_NOT_FOUND", "Create a new conversation.");
        }
        if (body.id() == null || !session.decide(body.id(), body.approved())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ChatErrors.ApiError("APPROVAL_EXPIRED", "Approval expired."));
        }
        return ResponseEntity.ok().build();
    }

    private AgentSessions.Session existing(HttpServletRequest request) {
        return sessions.get(request.getHeader(SESSION_HEADER));
    }

    record Prompt(String prompt, String model, String reasoningLevel, AgentMode mode) {}
    record Decision(String id, boolean approved) {}
    record ChatEvent(java.util.UUID requestId, String type, Object data) {}

}
