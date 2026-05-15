package com.wex.currency.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response for retrieving a stored transaction converted to a target currency (Requirement #2).
 *
 * <p>Includes every field the brief requires: the identifier, description, transaction date,
 * the original USD amount, the exchange rate used, and the converted amount.
 */
public record ConvertedTransactionResponse(
        UUID id,
        String description,
        LocalDate transactionDate,
        BigDecimal originalAmountUsd,
        String targetCurrency,
        BigDecimal exchangeRate,
        LocalDate exchangeRateDate,
        BigDecimal convertedAmount
) {
}
