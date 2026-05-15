package com.wex.currency.dto;

import com.wex.currency.domain.PurchaseTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Response returned after storing a transaction (Requirement #1). */
public record TransactionResponse(
        UUID id,
        String description,
        LocalDate transactionDate,
        BigDecimal purchaseAmount
) {
    public static TransactionResponse from(PurchaseTransaction tx) {
        return new TransactionResponse(
                tx.getId(), tx.getDescription(), tx.getTransactionDate(), tx.getPurchaseAmountUsd());
    }
}
