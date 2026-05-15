package com.wex.currency.exception;

import java.util.UUID;

/** Thrown when a transaction id is requested but does not exist. Mapped to HTTP 404. */
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID id) {
        super("No transaction found with id " + id);
    }
}
