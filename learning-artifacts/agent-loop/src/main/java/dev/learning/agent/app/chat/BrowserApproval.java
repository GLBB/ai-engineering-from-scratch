package dev.learning.agent.app.chat;

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
        Pending request = new Pending(UUID.randomUUID().toString(), new CompletableFuture<>());
        if (!pending.compareAndSet(null, request)) return false;
        try {
            sink.emit("approval", new ApprovalEvent(request.id(), kind, content));
            return request.decision().get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            pending.compareAndSet(request, null);
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

    record ApprovalEvent(String id, String kind, String content) {}
    private record Pending(String id, CompletableFuture<Boolean> decision) {}
}
