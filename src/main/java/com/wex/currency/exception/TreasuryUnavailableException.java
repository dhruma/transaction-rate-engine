package com.wex.currency.exception;

/**
 * Thrown when the Treasury Reporting Rates of Exchange API cannot be reached or returns a
 * server error. Mapped to HTTP 503 — an infrastructure fault, not a business outcome.
 */
public class TreasuryUnavailableException extends RuntimeException {
    public TreasuryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
