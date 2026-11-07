package dev.learning.agent.app.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserApprovalTest {
    @Test
    void commandNeedsMatchingApproval() throws Exception {
        LinkedBlockingQueue<BrowserApproval.ApprovalEvent> events = new LinkedBlockingQueue<>();
        BrowserApproval approval = new BrowserApproval((type, data) -> {
            assertEquals("approval", type);
            events.add((BrowserApproval.ApprovalEvent) data);
        });

        CompletableFuture<Boolean> answer = CompletableFuture.supplyAsync(() -> approval.approveCommand("pwd"));
        BrowserApproval.ApprovalEvent event = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("command", event.kind());
        assertEquals("pwd", event.content());
        assertFalse(approval.decide("wrong-id", true));
        assertTrue(approval.decide(event.id(), true));
        assertTrue(answer.get(2, TimeUnit.SECONDS));
        assertFalse(approval.decide(event.id(), true));
    }

    @Test
    void outputNeedsItsOwnDecision() throws Exception {
        LinkedBlockingQueue<BrowserApproval.ApprovalEvent> events = new LinkedBlockingQueue<>();
        BrowserApproval approval =
            new BrowserApproval((type, data) -> events.add((BrowserApproval.ApprovalEvent) data));

        CompletableFuture<Boolean> answer = CompletableFuture.supplyAsync(() -> approval.approveOutput("secret"));
        BrowserApproval.ApprovalEvent event = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("output", event.kind());
        assertEquals("secret", event.content());
        assertTrue(approval.decide(event.id(), false));
        assertFalse(answer.get(2, TimeUnit.SECONDS));
    }

    @Test
    void cancellationRejectsPendingCommand() throws Exception {
        LinkedBlockingQueue<BrowserApproval.ApprovalEvent> events = new LinkedBlockingQueue<>();
        BrowserApproval approval =
            new BrowserApproval((type, data) -> events.add((BrowserApproval.ApprovalEvent) data));

        CompletableFuture<Boolean> answer = CompletableFuture.supplyAsync(() -> approval.approveCommand("pwd"));
        assertNotNull(events.poll(2, TimeUnit.SECONDS));
        approval.cancel();
        assertFalse(answer.get(2, TimeUnit.SECONDS));
    }
}
