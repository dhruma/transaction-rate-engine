package com.wex.currency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps a client-supplied {@code Idempotency-Key} to the transaction it originally created.
 *
 * <p>{@link #requestHash} lets us detect the "same key, different body" case and reject it
 * rather than silently returning a record that does not match the new request.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // Required by JPA.
    }

    public IdempotencyRecord(String idempotencyKey, UUID transactionId, String requestHash,
                             Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
