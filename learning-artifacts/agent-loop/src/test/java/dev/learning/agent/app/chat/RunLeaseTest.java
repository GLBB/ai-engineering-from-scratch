package dev.learning.agent.app.chat;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelSettings;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RunLeaseTest {
    private AgentSessions sessions(ChatModel model) { return new AgentSessions(model, Path.of("."), null); }
    @Test void cancellationBeforeExecutionReleasesAndLateCallbackCannotStart() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        AgentSessions.Session session = sessions((r,t,c) -> { called.set(true); return new AssistantTurn("ok", List.of(), "stop"); }).create();
        RunLease lease = session.accept("hi", ModelSettings.defaults(), AgentMode.REACT);
        lease.cancel("closed before streaming starts");
        assertFalse(session.status().running());
        assertNull(session.execute(lease, (t,d) -> {}));
        assertFalse(called.get()); assertEquals(RunLease.State.CLOSED, lease.state());
        assertEquals(AgentMode.REACT, session.status().mode());
    }
    @Test void runningCancellationRetainsOwnershipUntilExecutionCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        AgentSessions.Session session = sessions((r,t,c) -> {
            try (AutoCloseable ignored = c.onCancel(cancelled::countDown)) {
                entered.countDown();
                if (!cleanup.await(3, TimeUnit.SECONDS)) throw new AssertionError("test cleanup gate timed out");
                c.check(); return new AssistantTurn("ok", List.of(), "stop");
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) { throw new java.io.IOException(e); }
        }).create();
        RunLease lease = session.accept("hi", ModelSettings.defaults(), AgentMode.REACT);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> assertThrows(InterruptedException.class,
                () -> session.execute(lease, (t,d) -> {})));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        lease.cancel("disconnect");
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(session.status().running());
        assertThrows(ApiFailure.class, () -> session.accept("another", ModelSettings.defaults(), null));
        cleanup.countDown(); worker.get(3, TimeUnit.SECONDS);
        assertFalse(session.status().running());
    }
    @Test void staleCompletionAndTimeoutNeverReleaseNewLease() {
        AgentSessions.Session session = sessions((r,t,c) -> new AssistantTurn("ok", List.of(), "stop")).create();
        RunLease first = session.accept("first", ModelSettings.defaults(), AgentMode.REWOO);
        first.cancel("before start");
        RunLease next = session.accept("second", ModelSettings.defaults(), null);
        first.finish(); first.cancel("late timeout");
        new AgentRunInterceptor(first).afterCompletion(null, null);
        assertEquals(next.requestId(), session.status().activeRequestId());
        assertTrue(session.status().running()); next.cancel("test cleanup");
    }
    @Test void concurrentFirstRequestsOnlyBindOneMode() throws Exception {
        AgentSessions.Session session = sessions((r,t,c) -> new AssistantTurn("ok", List.of(), "stop")).create();
        CyclicBarrier gate = new CyclicBarrier(2);
        java.util.function.Function<AgentMode, RunLease> accept = mode -> {
            try { gate.await(2, TimeUnit.SECONDS); return session.accept("first", ModelSettings.defaults(), mode); }
            catch (ApiFailure conflict) { assertEquals("RUN_IN_PROGRESS", conflict.code()); return null; }
            catch (Exception error) { throw new RuntimeException(error); }
        };
        var first = CompletableFuture.supplyAsync(() -> accept.apply(AgentMode.REWOO));
        var second = CompletableFuture.supplyAsync(() -> accept.apply(AgentMode.PLAN_EXECUTE));
        RunLease a = first.get(3, TimeUnit.SECONDS), b = second.get(3, TimeUnit.SECONDS);
        assertTrue((a == null) != (b == null));
        RunLease winner = a == null ? b : a;
        assertEquals(winner.requestId(), session.status().activeRequestId());
        winner.cancel("cleanup");
    }

    @Test void runDeadlineAlsoReleasesAcceptedLease() {
        AgentSessions.Session session = sessions((r,t,c) -> new AssistantTurn("ok", List.of(), "stop")).create();
        RunLease lease = session.accept("hi", ModelSettings.defaults(), null);
        lease.context().control().cancel("deadline before startup");
        assertFalse(session.status().running()); assertFalse(lease.tryStart());
    }
}
