package dev.learning.agent.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.app.LocalRequest;
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AgentSessions.Session session = sessions.create();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Ready(session.id()));
    }

    @PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> chat(@RequestBody Prompt body, HttpServletRequest request) {
        if (!LocalRequest.hasSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AgentSessions.Session session = existing(request);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean invalidPrompt = body.prompt() == null || body.prompt().isBlank()
                || body.prompt().length() > 10_000;
        if (invalidPrompt) {
            return ResponseEntity.badRequest().build();
        }
        ModelSettings settings;
        try {
            String model = body.model() == null ? ModelSettings.DEFAULT_MODEL : body.model();
            String level = body.reasoningLevel() == null ? "off" : body.reasoningLevel();
            settings = new ModelSettings(model, level);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!session.begin()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        StreamingResponseBody stream = output -> {
            EventSink sink = (type, data) -> {
                try {
                    byte[] line = (json.writeValueAsString(new ChatEvent(type, data)) + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    output.write(line);
                    output.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            try {
                session.run(body.prompt(), settings, sink);
                sink.emit("done", "");
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Agent failed." : e.getMessage();
                try {
                    sink.emit("error", message);
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AgentSessions.Session session = existing(request);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body.id() == null || !session.decide(body.id(), body.approved())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage("Approval expired."));
        }
        return ResponseEntity.ok().build();
    }

    private AgentSessions.Session existing(HttpServletRequest request) {
        return sessions.get(request.getHeader(SESSION_HEADER));
    }

    record Prompt(String prompt, String model, String reasoningLevel) {}
    record Decision(String id, boolean approved) {}
    record ChatEvent(String type, Object data) {}
    record ErrorMessage(String error) {}
    record Ready(String sessionId) {}
}
