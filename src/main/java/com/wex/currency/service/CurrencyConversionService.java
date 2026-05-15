package com.wex.currency.service;

import com.wex.currency.client.TreasuryRate;
import com.wex.currency.client.TreasuryRatesClient;
import com.wex.currency.domain.PurchaseTransaction;
import com.wex.currency.dto.ConvertedTransactionResponse;
import com.wex.currency.exception.CurrencyConversionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Converts a stored purchase into a target currency (Requirement #2).
 *
 * <p>Rule, taken directly from the brief: use a rate whose date is <em>on or before</em> the
 * purchase date and <em>within the prior 6 months</em>; otherwise the purchase cannot be
 * converted and an error is returned.
 */
@Service
public class CurrencyConversionService {

    /** The brief's window: a usable rate must not be older than this many months. */
    private static final int MAX_RATE_AGE_MONTHS = 6;

    private final TreasuryRatesClient treasuryRatesClient;

    public CurrencyConversionService(TreasuryRatesClient treasuryRatesClient) {
        this.treasuryRatesClient = treasuryRatesClient;
    }

    /**
     * @throws CurrencyConversionException if no rate exists on or before the purchase date
     *         within the prior 6 months (HTTP 422)
     */
    public ConvertedTransactionResponse convert(PurchaseTransaction tx, String currency) {
        LocalDate purchaseDate = tx.getTransactionDate();

        Optional<TreasuryRate> maybeRate =
                treasuryRatesClient.findLatestRateOnOrBefore(currency, purchaseDate);

        TreasuryRate rate = maybeRate.orElseThrow(() -> conversionUnavailable(currency));

        // Inclusive lower bound: a rate dated exactly six months before the purchase is still
        // acceptable. minusMonths(6) is calendar-aware (handles month-length differences).
        LocalDate earliestAcceptable = purchaseDate.minusMonths(MAX_RATE_AGE_MONTHS);
        if (rate.recordDate().isBefore(earliestAcceptable)) {
            throw conversionUnavailable(currency);
        }

        // Multiply at full rate precision, then round the final money value to the cent.
        BigDecimal converted = tx.getPurchaseAmountUsd()
                .multiply(rate.exchangeRate())
                .setScale(2, RoundingMode.HALF_UP);

        return new ConvertedTransactionResponse(
                tx.getId(),
                tx.getDescription(),
                tx.getTransactionDate(),
                tx.getPurchaseAmountUsd(),
                currency,
                rate.exchangeRate(),
                rate.recordDate(),
                converted);
    }

    private CurrencyConversionException conversionUnavailable(String currency) {
        return new CurrencyConversionException(
                "The stored purchase cannot be converted to '" + currency
                        + "': no exchange rate is available on or before the purchase date "
                        + "within the last 6 months.");
    }
}
