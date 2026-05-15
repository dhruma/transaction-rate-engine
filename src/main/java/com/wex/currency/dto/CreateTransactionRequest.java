package com.wex.currency.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for storing a purchase transaction (Requirement #1).
 *
 * <p>Field rules from the brief are enforced by Bean Validation:
 * description must not exceed 50 characters, the date must be a valid ISO date, and the amount
 * must be a valid positive value. The amount is rounded to the nearest cent by the service.
 */
public record CreateTransactionRequest(

        @NotBlank(message = "description must not be blank")
        @Size(max = 50, message = "description must not exceed 50 characters")
        String description,

        @NotNull(message = "transactionDate is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate transactionDate,

        @NotNull(message = "purchaseAmount is required")
        @Positive(message = "purchaseAmount must be a positive amount")
        BigDecimal purchaseAmount
) {
}
