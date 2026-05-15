package com.wex.currency.client;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single exchange-rate record returned by the Treasury API.
 *
 * @param countryCurrencyDesc the currency identifier, e.g. {@code "Canada-Dollar"}
 * @param exchangeRate        units of the foreign currency per 1 USD, at the API's native precision
 * @param recordDate          the date the rate is recorded for
 */
public record TreasuryRate(String countryCurrencyDesc, BigDecimal exchangeRate, LocalDate recordDate) {
}
