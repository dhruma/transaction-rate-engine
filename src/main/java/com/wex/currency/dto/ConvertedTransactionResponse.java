package com.wex.currency.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response for retrieving a stored transaction converted to a target currency (Requirement #2).
 *
 * <p>Includes every field the brief requires: the identifier, description, transaction date,
 * the original USD amount, the exchange rate used, and the converted amount. {@code isoCode}
 * is the ISO 4217 code for the target currency when known (the Treasury dataset has no ISO
 * codes, so this is best-effort and may be {@code null}).
 */
public record ConvertedTransactionResponse(
        UUID id,
        String description,
        LocalDate transactionDate,
        BigDecimal originalAmountUsd,
        String targetCurrency,
        String isoCode,
        BigDecimal exchangeRate,
        LocalDate exchangeRateDate,
        BigDecimal convertedAmount
) {
}
