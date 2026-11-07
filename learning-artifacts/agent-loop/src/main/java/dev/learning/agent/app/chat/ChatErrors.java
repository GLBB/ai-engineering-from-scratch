package dev.learning.agent.app.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChatController.class)
final class ChatErrors {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiError> api(ApiFailure error) {
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(), error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalid(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid JSON or execution mode."));
    }

    record ApiError(String code, String message) {
    }
}
