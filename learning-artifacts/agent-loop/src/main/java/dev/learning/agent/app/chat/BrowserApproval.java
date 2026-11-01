package dev.learning.agent.app.chat;

import dev.learning.agent.core.RunControl;
import dev.learning.agent.core.RunScope;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Bridges Bash's blocking approvals to browser requests. */
final class BrowserApproval {
    private final EventSink sink;
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    BrowserApproval(EventSink sink) {
        this.sink = sink;
    }

    boolean approveCommand(String command) {
        return request("command", command);
    }

    boolean approveOutput(String output) {
        return request("output", output);
    }

    private boolean request(String kind, String content) {
        return request(kind, content, null, null);
    }

    boolean request(String kind, String content, RunScope scope, RunControl control) {
        Pending request = new Pending(UUID.randomUUID().toString(), new CompletableFuture<>());
        if (!pending.compareAndSet(null, request)) return false;
        AutoCloseable registration = control == null ? () -> {} : control.onCancel(() -> request.decision().complete(false));
        try {
            if (control != null) control.check();
            sink.emit("approval", new ApprovalEvent(request.id(), kind, content, scope == null ? null : scope.stepId()));
            return request.decision().get(control == null ? 300_000 : Math.min(300_000, control.remaining().toMillis()), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            pending.compareAndSet(request, null);
            try { registration.close(); } catch (Exception ignored) { /* Unregister only. */ }
        }
    }

    boolean decide(String id, boolean approved) {
        Pending request = pending.get();
        return request != null && request.id().equals(id) && request.decision().complete(approved);
    }

    void cancel() {
        Pending request = pending.get();
        if (request != null) {
            request.decision().complete(false);
        }
    }

    record ApprovalEvent(String id, String kind, String content, String stepId) {}
    private record Pending(String id, CompletableFuture<Boolean> decision) {}
}
