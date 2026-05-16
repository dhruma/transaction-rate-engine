package com.wex.currency.service;

import java.util.Map;
import java.util.Optional;

/**
 * Maps the Treasury API's {@code country_currency_desc} values (e.g. {@code "Canada-Dollar"})
 * to ISO 4217 codes.
 *
 * <p>The Treasury Reporting Rates of Exchange dataset does not publish ISO codes, so this is a
 * curated, best-effort lookup over the common currencies. Anything not in the table simply has
 * no ISO code (the UI then shows the Treasury name alone) — that is an accepted limitation,
 * not an error. Every {@code *-Euro} variant resolves to {@code EUR} via a rule rather than
 * enumerating each eurozone member.
 */
public final class CurrencyCatalog {

    /** ISO 4217 code for a US-dollar amount that needs no conversion. */
    public static final String USD = "USD";

    private static final Map<String, String> DESC_TO_ISO = Map.ofEntries(
            Map.entry("Australia-Dollar", "AUD"),
            Map.entry("Brazil-Real", "BRL"),
            Map.entry("Canada-Dollar", "CAD"),
            Map.entry("China-Yuan Renminbi", "CNY"),
            Map.entry("Czech Republic-Koruna", "CZK"),
            Map.entry("Denmark-Krone", "DKK"),
            Map.entry("Hong Kong-Dollar", "HKD"),
            Map.entry("Hungary-Forint", "HUF"),
            Map.entry("India-Rupee", "INR"),
            Map.entry("Indonesia-Rupiah", "IDR"),
            Map.entry("Japan-Yen", "JPY"),
            Map.entry("Malaysia-Ringgit", "MYR"),
            Map.entry("Mexico-Peso", "MXN"),
            Map.entry("New Zealand-Dollar", "NZD"),
            Map.entry("Norway-Krone", "NOK"),
            Map.entry("Philippines-Peso", "PHP"),
            Map.entry("Poland-Zloty", "PLN"),
            Map.entry("Russia-Ruble", "RUB"),
            Map.entry("Saudi Arabia-Riyal", "SAR"),
            Map.entry("Singapore-Dollar", "SGD"),
            Map.entry("South Africa-Rand", "ZAR"),
            Map.entry("Sweden-Krona", "SEK"),
            Map.entry("Switzerland-Franc", "CHF"),
            Map.entry("Thailand-Baht", "THB"),
            Map.entry("Turkey-Lira", "TRY"),
            Map.entry("United Arab Emirates-Dirham", "AED"),
            Map.entry("United Kingdom-Pound", "GBP"));

    private CurrencyCatalog() {
    }

    /**
     * ISO 4217 code for a Treasury currency description, if known.
     *
     * @param description Treasury {@code country_currency_desc}, or the literal {@code "USD"}
     */
    public static Optional<String> isoFor(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        String desc = description.trim();
        if (USD.equalsIgnoreCase(desc)) {
            return Optional.of(USD);
        }
        if (desc.toLowerCase().endsWith("-euro")) {
            return Optional.of("EUR");
        }
        return Optional.ofNullable(DESC_TO_ISO.get(desc));
    }

    /** {@code "Canada-Dollar (CAD)"} when the ISO code is known, otherwise just the name. */
    public static String label(String description) {
        return isoFor(description)
                .map(iso -> description + " (" + iso + ")")
                .orElse(description);
    }

    /** True when the requested target currency is US dollars (no conversion needed). */
    public static boolean isUsd(String currency) {
        return currency != null && USD.equalsIgnoreCase(currency.trim());
    }
}
