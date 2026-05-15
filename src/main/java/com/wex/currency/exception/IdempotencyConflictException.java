package com.wex.currency.exception;

/**
 * Thrown when an {@code Idempotency-Key} is reused with a different request body. Mapped to
 * HTTP 422 — silently returning the original (mismatched) record would hide a client bug.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
