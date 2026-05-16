package com.wex.currency.exception;

/**
 * Thrown when the requested {@code currency} is syntactically invalid — blank, too long, or
 * containing a character ({@code ,} or {@code :}) that is a separator in the Treasury filter
 * query. Mapped to HTTP 400.
 *
 * <p>Rejecting these values up front both gives the caller a clear 400 (instead of a
 * misleading 422) and prevents the value from being interpolated into the upstream Treasury
 * filter expression (filter-injection defence).
 */
public class InvalidCurrencyException extends RuntimeException {
    public InvalidCurrencyException(String message) {
        super(message);
    }
}
