package dev.learning.agent.app.chat;

final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;

    ApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
