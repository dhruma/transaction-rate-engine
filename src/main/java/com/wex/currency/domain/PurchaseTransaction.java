package com.wex.currency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A stored purchase transaction (Requirement #1).
 *
 * <p>The monetary amount is persisted as {@link BigDecimal} with scale 2 — money is never
 * represented with {@code double}/{@code float} to avoid binary rounding error.
 */
@Entity
@Table(name = "purchase_transaction")
public class PurchaseTransaction {

    /** Server-generated unique identifier for the purchase. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String description;

    @Column(nullable = false)
    private LocalDate transactionDate;

    /** Original purchase amount in USD, rounded to the nearest cent (scale 2). */
    @Column(name = "purchase_amount_usd", nullable = false, precision = 19, scale = 2)
    private BigDecimal purchaseAmountUsd;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchaseTransaction() {
        // Required by JPA.
    }

    public PurchaseTransaction(UUID id, String description, LocalDate transactionDate,
                               BigDecimal purchaseAmountUsd, Instant createdAt) {
        this.id = id;
        this.description = description;
        this.transactionDate = transactionDate;
        this.purchaseAmountUsd = purchaseAmountUsd;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public BigDecimal getPurchaseAmountUsd() {
        return purchaseAmountUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
