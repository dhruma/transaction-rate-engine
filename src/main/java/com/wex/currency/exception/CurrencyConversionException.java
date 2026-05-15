package com.wex.currency.exception;

/**
 * Thrown when a stored purchase cannot be converted to the requested currency because no
 * exchange rate exists on or before the purchase date within the prior 6 months.
 *
 * <p>This is an <em>expected</em> business outcome (mapped to HTTP 422) and is deliberately
 * distinct from {@link TreasuryUnavailableException}, which represents an infrastructure fault.
 */
public class CurrencyConversionException extends RuntimeException {
    public CurrencyConversionException(String message) {
        super(message);
    }
}
