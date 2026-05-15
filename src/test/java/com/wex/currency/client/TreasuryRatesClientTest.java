package com.wex.currency.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.currency.exception.TreasuryUnavailableException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TreasuryRatesClient} against a stubbed Treasury API (WireMock).
 *
 * <p>Covers the logic that only lives in the client and is otherwise reached only indirectly:
 * snake_case JSON mapping, the de-duplicate + sort in {@code listCurrencies}, and the
 * translation of every transport failure (timeout, connection refused, 4xx, 5xx) into a
 * {@link TreasuryUnavailableException} so the API can answer 503 rather than a generic 500.
 */
class TreasuryRatesClientTest {

    private WireMockServer wireMock;

    private TreasuryRatesClient clientFor(int readTimeoutMs) {
        return new TreasuryRatesClient(
                "http://localhost:" + wireMock.port() + "/rates", 1000, readTimeoutMs);
    }

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private void stub(int status, String body) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/rates"))
                .willReturn(WireMock.aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void parsesLatestRateRecord() {
        stub(200, """
            {"data":[{"country_currency_desc":"Canada-Dollar",
                      "exchange_rate":"1.345","record_date":"2023-06-30"}]}""");

        Optional<TreasuryRate> rate = clientFor(2000)
                .findLatestRateOnOrBefore("Canada-Dollar", LocalDate.of(2023, 7, 15));

        assertThat(rate).isPresent();
        assertThat(rate.get().countryCurrencyDesc()).isEqualTo("Canada-Dollar");
        assertThat(rate.get().exchangeRate()).isEqualByComparingTo("1.345");
        assertThat(rate.get().recordDate()).isEqualTo(LocalDate.of(2023, 6, 30));
    }

    @Test
    void returnsEmptyWhenApiHasNoMatchingRecord() {
        stub(200, "{\"data\":[]}");

        assertThat(clientFor(2000)
                .findLatestRateOnOrBefore("Canada-Dollar", LocalDate.of(2023, 7, 15)))
                .isEmpty();
    }

    @Test
    void listCurrenciesDeduplicatesAndSortsCaseInsensitively() {
        stub(200, """
            {"data":[
              {"country_currency_desc":"Zambia-Kwacha","record_date":"2023-12-31"},
              {"country_currency_desc":"argentina-Peso","record_date":"2023-12-31"},
              {"country_currency_desc":"Zambia-Kwacha","record_date":"2023-09-30"},
              {"country_currency_desc":"Brazil-Real","record_date":"2023-12-31"}
            ]}""");

        List<String> currencies = clientFor(2000).listCurrencies();

        assertThat(currencies).containsExactly("argentina-Peso", "Brazil-Real", "Zambia-Kwacha");
    }

    @Test
    void mapsServerErrorToTreasuryUnavailable() {
        stub(500, "upstream boom");

        assertThatThrownBy(() -> clientFor(2000)
                .findLatestRateOnOrBefore("Canada-Dollar", LocalDate.of(2023, 7, 15)))
                .isInstanceOf(TreasuryUnavailableException.class);
    }

    @Test
    void mapsClientErrorToTreasuryUnavailable() {
        stub(400, "{\"error\":\"Invalid Query Param\"}");

        assertThatThrownBy(() -> clientFor(2000).listCurrencies())
                .isInstanceOf(TreasuryUnavailableException.class);
    }

    @Test
    void mapsReadTimeoutToTreasuryUnavailable() {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/rates"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withFixedDelay(800)
                        .withBody("{\"data\":[]}")));

        assertThatThrownBy(() -> clientFor(200) // read timeout < server delay
                .findLatestRateOnOrBefore("Canada-Dollar", LocalDate.of(2023, 7, 15)))
                .isInstanceOf(TreasuryUnavailableException.class);
    }

    @Test
    void mapsConnectionFailureToTreasuryUnavailable() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort(); // closed as soon as the try-block exits
        }
        TreasuryRatesClient client = new TreasuryRatesClient(
                "http://localhost:" + deadPort + "/rates", 500, 500);

        assertThatThrownBy(() -> client.listCurrencies())
                .isInstanceOf(TreasuryUnavailableException.class);
    }
}
