package dev.learning.agent.core;


public interface ApprovalGateway {
    boolean approve(String kind, String content, RunScope scope, RunControl control);
}
