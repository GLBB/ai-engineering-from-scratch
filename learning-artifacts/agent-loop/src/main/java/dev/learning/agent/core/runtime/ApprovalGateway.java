package dev.learning.agent.core.runtime;

public interface ApprovalGateway {
    boolean approve(String kind, String content, RunScope scope, RunControl control);
}
