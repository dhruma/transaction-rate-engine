package com.wex.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wex.currency.client.TreasuryRate;
import com.wex.currency.client.TreasuryRatesClient;
import com.wex.currency.domain.PurchaseTransaction;
import com.wex.currency.dto.ConvertedTransactionResponse;
import com.wex.currency.exception.CurrencyConversionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the core conversion + 6-month-window rule (Requirement #2). */
@ExtendWith(MockitoExtension.class)
class CurrencyConversionServiceTest {

    @Mock
    private TreasuryRatesClient client;

    private static final LocalDate PURCHASE_DATE = LocalDate.of(2023, 7, 15);

    private CurrencyConversionService service() {
        return new CurrencyConversionService(client);
    }

    private PurchaseTransaction tx(BigDecimal amount) {
        return new PurchaseTransaction(UUID.randomUUID(), "Test", PURCHASE_DATE, amount, Instant.now());
    }

    @Test
    void convertsUsingRateAndRoundsHalfUpToCent() {
        when(client.findLatestRateOnOrBefore("Canada-Dollar", PURCHASE_DATE))
                .thenReturn(Optional.of(new TreasuryRate(
                        "Canada-Dollar", new BigDecimal("1.345"), LocalDate.of(2023, 6, 30))));

        ConvertedTransactionResponse r =
                service().convert(tx(new BigDecimal("100.00")), "Canada-Dollar");

        // 100.00 * 1.345 = 134.500 -> HALF_UP to 2dp -> 134.50
        assertThat(r.convertedAmount()).isEqualByComparingTo("134.50");
        assertThat(r.exchangeRate()).isEqualByComparingTo("1.345");
        assertThat(r.targetCurrency()).isEqualTo("Canada-Dollar");
    }

    @Test
    void roundsHalfUpAtTheHalfCent() {
        when(client.findLatestRateOnOrBefore("X", PURCHASE_DATE))
                .thenReturn(Optional.of(new TreasuryRate("X", new BigDecimal("1.005"),
                        PURCHASE_DATE)));

        // 1.00 * 1.005 = 1.005 -> HALF_UP -> 1.01
        assertThat(service().convert(tx(new BigDecimal("1.00")), "X").convertedAmount())
                .isEqualByComparingTo("1.01");
    }

    @Test
    void acceptsRateDatedExactlySixMonthsBeforePurchase() {
        LocalDate boundary = PURCHASE_DATE.minusMonths(6); // 2023-01-15, inclusive
        when(client.findLatestRateOnOrBefore("EUR", PURCHASE_DATE))
                .thenReturn(Optional.of(new TreasuryRate("EUR", new BigDecimal("0.9"), boundary)));

        assertThat(service().convert(tx(new BigDecimal("50.00")), "EUR").exchangeRateDate())
                .isEqualTo(boundary);
    }

    @Test
    void rejectsRateOlderThanSixMonths() {
        LocalDate tooOld = PURCHASE_DATE.minusMonths(6).minusDays(1);
        when(client.findLatestRateOnOrBefore("EUR", PURCHASE_DATE))
                .thenReturn(Optional.of(new TreasuryRate("EUR", new BigDecimal("0.9"), tooOld)));

        assertThatThrownBy(() -> service().convert(tx(new BigDecimal("50.00")), "EUR"))
                .isInstanceOf(CurrencyConversionException.class)
                .hasMessageContaining("cannot be converted");
    }

    @Test
    void rejectsWhenNoRateAvailable() {
        when(client.findLatestRateOnOrBefore("EUR", PURCHASE_DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().convert(tx(new BigDecimal("50.00")), "EUR"))
                .isInstanceOf(CurrencyConversionException.class);
    }

    @Test
    void usdToUsdPassesThroughAtRateOneWithoutCallingTreasury() {
        ConvertedTransactionResponse r = service().convert(tx(new BigDecimal("100.00")), "usd");

        assertThat(r.targetCurrency()).isEqualTo("USD");
        assertThat(r.isoCode()).isEqualTo("USD");
        assertThat(r.exchangeRate()).isEqualByComparingTo("1.00");
        assertThat(r.convertedAmount()).isEqualByComparingTo("100.00");
        assertThat(r.exchangeRateDate()).isEqualTo(PURCHASE_DATE);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsCurrencyWithFilterSeparatorWithoutCallingTreasury() {
        assertThatThrownBy(() -> service().convert(
                tx(new BigDecimal("10.00")), "Canada-Dollar,record_date:gte:2030-01-01"))
                .isInstanceOf(com.wex.currency.exception.InvalidCurrencyException.class);
        assertThatThrownBy(() -> service().convert(tx(new BigDecimal("10.00")), "  "))
                .isInstanceOf(com.wex.currency.exception.InvalidCurrencyException.class);
        verifyNoInteractions(client);
    }

    @Test
    void populatesIsoCodeForKnownCurrency() {
        when(client.findLatestRateOnOrBefore("Canada-Dollar", PURCHASE_DATE))
                .thenReturn(Optional.of(new TreasuryRate(
                        "Canada-Dollar", new BigDecimal("1.3"), LocalDate.of(2023, 6, 30))));

        assertThat(service().convert(tx(new BigDecimal("10.00")), "Canada-Dollar").isoCode())
                .isEqualTo("CAD");
    }
}
