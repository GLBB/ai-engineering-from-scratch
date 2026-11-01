package dev.learning.agent.app.chat;

import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import java.util.concurrent.Callable;

/** Servlet lifecycle signals cancel this exact lease, never a later request. */
final class AgentRunInterceptor implements CallableProcessingInterceptor {
    private final RunLease lease;
    AgentRunInterceptor(RunLease lease) { this.lease = lease; }
    public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
        lease.cancel("HTTP async timeout"); return RESULT_NONE;
    }
    public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable error) {
        lease.cancel("HTTP async error"); return RESULT_NONE;
    }
    public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) { lease.cancel("HTTP completed"); }
}
