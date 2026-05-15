package com.wex.currency.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wex.currency.exception.TreasuryUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin client over the U.S. Treasury Reporting Rates of Exchange API.
 *
 * <p>This class is intentionally narrow: it fetches data and translates transport failures into
 * {@link TreasuryUnavailableException}. The 6-month business rule lives in
 * {@code CurrencyConversionService} so it can be unit-tested without the network.
 */
@Component
public class TreasuryRatesClient {

    private static final Logger log = LoggerFactory.getLogger(TreasuryRatesClient.class);

    private final RestClient restClient;

    public TreasuryRatesClient(
            @Value("${treasury.base-url}") String baseUrl,
            @Value("${treasury.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${treasury.read-timeout-ms}") int readTimeoutMs) {

        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /**
     * Returns the most recent exchange rate whose {@code record_date} is on or before
     * {@code onOrBefore} for the given currency, or empty if the API has no such record.
     *
     * <p>The 6-month acceptance window is applied by the caller, not here.
     *
     * @throws TreasuryUnavailableException if the API cannot be reached or returns a server error
     */
    public Optional<TreasuryRate> findLatestRateOnOrBefore(String currency, LocalDate onOrBefore) {
        // sort=-record_date + page[size]=1 => the API returns only the single newest qualifying row.
        TreasuryApiResponse response = get(uri -> uri
                .queryParam("fields", "country_currency_desc,exchange_rate,record_date")
                .queryParam("filter",
                        "country_currency_desc:eq:" + currency + ",record_date:lte:" + onOrBefore)
                .queryParam("sort", "-record_date")
                .queryParam("page[size]", "1")
                .build());

        if (response == null || response.data() == null || response.data().isEmpty()) {
            return Optional.empty();
        }
        TreasuryApiRecord r = response.data().getFirst();
        return Optional.of(new TreasuryRate(r.countryCurrencyDesc(), r.exchangeRate(), r.recordDate()));
    }

    /**
     * Returns the distinct currency identifiers the API exposes (used to populate the UI
     * dropdown). De-duplicated, preserving the most-recent-first order returned by the API.
     */
    public List<String> listCurrencies() {
        // record_date must be in `fields` because we sort by it (newest first) so the most
        // current currency set wins the de-duplication.
        TreasuryApiResponse response = get(uri -> uri
                .queryParam("fields", "country_currency_desc,record_date")
                .queryParam("sort", "-record_date")
                .queryParam("page[size]", "2000")
                .build());

        if (response == null || response.data() == null) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (TreasuryApiRecord r : response.data()) {
            distinct.add(r.countryCurrencyDesc());
        }
        List<String> result = new ArrayList<>(distinct);
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    private TreasuryApiResponse get(java.util.function.Function<
            org.springframework.web.util.UriBuilder, java.net.URI> uriSpec) {
        try {
            return restClient.get()
                    .uri(uriSpec)
                    .retrieve()
                    .body(TreasuryApiResponse.class);
        } catch (ResourceAccessException e) {
            // Connection refused / timeout / DNS — infrastructure fault, not "no rate".
            log.warn("Treasury API unreachable: {}", e.getMessage());
            throw new TreasuryUnavailableException("Treasury exchange-rate service is unavailable", e);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Any non-2xx (5xx or an unexpected 4xx) — the caller cannot recover from a
            // dependency fault, so surface it as "unavailable" rather than a generic 500.
            log.warn("Treasury API returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new TreasuryUnavailableException("Treasury exchange-rate service returned an error", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TreasuryApiResponse(List<TreasuryApiRecord> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TreasuryApiRecord(
            @JsonProperty("country_currency_desc") String countryCurrencyDesc,
            @JsonProperty("exchange_rate") BigDecimal exchangeRate,
            @JsonProperty("record_date") LocalDate recordDate) {
    }
}
