package dev.learning.agent.app;

import jakarta.servlet.http.HttpServletRequest;

/** Checks the host and origin of browser requests to the local application. */
public final class LocalRequest {
    private LocalRequest() {
    }

    public static boolean isLocalHost(HttpServletRequest request) {
        String host = request.getServerName();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    public static boolean hasSameOrigin(HttpServletRequest request) {
        if (!isLocalHost(request)) {
            return false;
        }
        String expected = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        return expected.equals(request.getHeader("Origin"));
    }
}
