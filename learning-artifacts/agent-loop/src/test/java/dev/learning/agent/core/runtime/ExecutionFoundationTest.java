package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolStatus;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionFoundationTest {
    @Test void reactCanUseAllEightModelRequests() {
        RunBudget budget = new DefaultRunBudget(AgentMode.REACT);
        for (int i = 0; i < 8; i++) budget.claimModel(ModelUse.EXECUTOR);
        assertThrows(BudgetExceededException.class, () -> budget.claimModel(ModelUse.EXECUTOR));
        assertEquals(8, budget.snapshot().modelCalls());
    }

    @Test void executorLeavesReviewAndFinalizerSlots() {
        RunBudget budget = new DefaultRunBudget(AgentMode.PLAN_EXECUTE);
        for (int i = 0; i < 33; i++) budget.claimModel(ModelUse.EXECUTOR);
        assertThrows(BudgetExceededException.class, () -> budget.claimModel(ModelUse.EXECUTOR));
        budget.claimModel(ModelUse.CONTROL);
        budget.claimModel(ModelUse.FINALIZER);
        assertEquals(35, budget.snapshot().modelCalls());
        assertThrows(BudgetExceededException.class, () -> budget.claimModel(ModelUse.FINALIZER));
    }

    @Test void revisionsAndFormatRepairHaveSeparateLimits() {
        RunBudget budget = new DefaultRunBudget(AgentMode.PLAN_EXECUTE);
        assertTrue(budget.claimFormatRepair());
        assertFalse(budget.claimFormatRepair());
        budget.claimRevision();
        budget.claimRevision();
        assertThrows(BudgetExceededException.class, budget::claimRevision);
    }

    @Test void deniedAttemptsDoNotCountAsExecuted() {
        RunBudget budget = new DefaultRunBudget(AgentMode.REACT);
        budget.claimToolAttempt();
        budget.recordToolResult(new ToolResult(ToolStatus.DENIED, "declined", "DENIED", false, false, false));
        assertEquals(1, budget.snapshot().toolAttempts());
        assertEquals(0, budget.snapshot().toolsExecuted());
    }

    @Test void cancellationIsIdempotentAndUnregisteredActionsDoNotRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (RunControl control = new DefaultRunControl(Duration.ofMinutes(1))) {
            control.onCancel(calls::incrementAndGet).close();
            control.onCancel(calls::incrementAndGet);
            control.cancel("stop");
            control.cancel("again");
            assertEquals(1, calls.get());
            assertThrows(InterruptedException.class, control::check);
        }
    }

    @Test void monotonicDeadlineIsCheckedWithoutSleeping() {
        AtomicLong clock = new AtomicLong();
        try (RunControl control = new DefaultRunControl(Duration.ofSeconds(1), clock::get)) {
            clock.set(Duration.ofSeconds(2).toNanos());
            assertThrows(InterruptedException.class, control::check);
        }
    }

    @Test void emptyOrTruncatedEvidenceCannotBindAndIdsCannotOverwrite() {
        EvidenceStore store = new InMemoryEvidenceStore();
        Evidence empty = new Evidence("E1", "S1", "search", "{}",
                new ToolResult(ToolStatus.SUCCESS, "no results", null, true, false, false), 1);
        store.put(empty);
        assertThrows(IllegalArgumentException.class, () -> store.requireUsable("E1"));
        assertThrows(IllegalArgumentException.class, () -> store.put(empty));
        assertEquals("E2", store.nextId());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot().clear());
    }
}
