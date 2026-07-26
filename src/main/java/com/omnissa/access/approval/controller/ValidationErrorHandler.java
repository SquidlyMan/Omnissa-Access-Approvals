package com.omnissa.access.approval.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Gives bean-validation failures the same {@code {"error": "..."}} shape every
 * other rejection uses.
 *
 * <p>Without this, a violated {@code @NotNull} or {@code @Size} returns Spring's
 * default body, which carries no {@code error} key — so a client that reads
 * {@code error} sees nothing and falls back to "Server error 400", telling the
 * user only that something was wrong. The constraint already knows what it
 * wanted; this just surfaces it.
 */
@RestControllerAdvice
public class ValidationErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationFailure(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);

        String message = first == null
                ? "The request was not valid."
                // "username must be between 4 and 50 characters" reads better
                // than the field name alone, and better than a status code.
                : first.getField() + " " + first.getDefaultMessage();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
