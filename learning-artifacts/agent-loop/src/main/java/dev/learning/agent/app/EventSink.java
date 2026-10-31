package dev.learning.agent.app;

/** Sends one structured event to the active browser response. */
@FunctionalInterface
interface EventSink {
    void emit(String type, Object data);
}
