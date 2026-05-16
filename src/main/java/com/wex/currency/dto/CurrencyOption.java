package com.wex.currency.dto;

/**
 * A selectable target currency for the UI dropdown.
 *
 * @param value the value to pass to the convert endpoint (Treasury {@code country_currency_desc},
 *              or the literal {@code "USD"})
 * @param label display text, including the ISO 4217 code when known, e.g. {@code "Canada-Dollar (CAD)"}
 */
public record CurrencyOption(String value, String label) {
}
