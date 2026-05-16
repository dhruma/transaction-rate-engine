package com.wex.currency.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CurrencyCatalogTest {

    @Test
    void mapsKnownCurrencyToIso() {
        assertThat(CurrencyCatalog.isoFor("Canada-Dollar")).contains("CAD");
        assertThat(CurrencyCatalog.isoFor("United Kingdom-Pound")).contains("GBP");
    }

    @Test
    void anyEuroVariantResolvesToEur() {
        assertThat(CurrencyCatalog.isoFor("Euro Zone-Euro")).contains("EUR");
        assertThat(CurrencyCatalog.isoFor("Germany-Euro")).contains("EUR");
        assertThat(CurrencyCatalog.isoFor("France-Euro")).contains("EUR");
    }

    @Test
    void unknownCurrencyHasNoIso() {
        assertThat(CurrencyCatalog.isoFor("Atlantis-Shell")).isEmpty();
        assertThat(CurrencyCatalog.isoFor(null)).isEmpty();
        assertThat(CurrencyCatalog.isoFor("  ")).isEmpty();
    }

    @Test
    void usdIsRecognisedCaseInsensitively() {
        assertThat(CurrencyCatalog.isUsd("USD")).isTrue();
        assertThat(CurrencyCatalog.isUsd(" usd ")).isTrue();
        assertThat(CurrencyCatalog.isUsd("Canada-Dollar")).isFalse();
        assertThat(CurrencyCatalog.isoFor("USD")).contains("USD");
    }

    @Test
    void labelAppendsIsoWhenKnown() {
        assertThat(CurrencyCatalog.label("Canada-Dollar")).isEqualTo("Canada-Dollar (CAD)");
        assertThat(CurrencyCatalog.label("Atlantis-Shell")).isEqualTo("Atlantis-Shell");
    }
}
