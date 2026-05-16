package com.wex.currency.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end HTTP tests: real Spring context + in-memory H2, with the Treasury API stubbed by
 * WireMock so the suite is deterministic and offline. Covers both requirements and their
 * unhappy paths (the cases a take-home is graded on).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerIntegrationTest {

    // Started in a static initializer so the server (and its port) exists before Spring
    // evaluates @DynamicPropertySource during context creation.
    private static final WireMockServer wireMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        wireMock.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("treasury.base-url", () -> "http://localhost:" + wireMock.port() + "/rates");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:itdb;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static String rateBody(String currency, String rate, String recordDate) {
        return """
            {"data":[{"country_currency_desc":"%s","exchange_rate":"%s","record_date":"%s"}]}
            """.formatted(currency, rate, recordDate);
    }

    private void stubTreasury(String responseBody, int status) {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/rates"))
                .willReturn(WireMock.aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    private String storeTransaction(String description, String date, String amount) throws Exception {
        String response = mockMvc.perform(post("/api/transactions")
                        .contentType("application/json")
                        .content("""
                            {"description":"%s","transactionDate":"%s","purchaseAmount":%s}
                            """.formatted(description, date, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    // ---- Requirement #1 ----

    @Test
    void storesTransactionAndAssignsId() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType("application/json")
                        .content("""
                            {"description":"Office chair","transactionDate":"2023-07-15",
                             "purchaseAmount":199.999}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // 199.999 rounded HALF_UP to nearest cent.
                .andExpect(jsonPath("$.purchaseAmount").value(200.00));
    }

    @Test
    void rejectsDescriptionLongerThan50Chars() throws Exception {
        String longDesc = "x".repeat(51);
        mockMvc.perform(post("/api/transactions")
                        .contentType("application/json")
                        .content("""
                            {"description":"%s","transactionDate":"2023-07-15",
                             "purchaseAmount":10.00}""".formatted(longDesc)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("50 characters")));
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType("application/json")
                        .content("""
                            {"description":"Refund","transactionDate":"2023-07-15",
                             "purchaseAmount":-5.00}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType("application/json")
                        .content("""
                            {"description":"Bad date","transactionDate":"15-07-2023",
                             "purchaseAmount":5.00}"""))
                .andExpect(status().isBadRequest());
    }

    // ---- Requirement #2 ----

    @Test
    void retrievesConvertedAmount() throws Exception {
        String id = storeTransaction("Conference", "2023-07-15", "100.00");
        stubTreasury(rateBody("Canada-Dollar", "1.345", "2023-06-30"), 200);

        mockMvc.perform(get("/api/transactions/" + id).param("currency", "Canada-Dollar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAmountUsd").value(100.00))
                .andExpect(jsonPath("$.exchangeRate").value(1.345))
                .andExpect(jsonPath("$.convertedAmount").value(134.50));
    }

    @Test
    void returns422WhenNoRateWithinSixMonths() throws Exception {
        String id = storeTransaction("Old purchase", "2023-07-15", "100.00");
        stubTreasury("{\"data\":[]}", 200);

        mockMvc.perform(get("/api/transactions/" + id).param("currency", "Canada-Dollar"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CONVERSION_UNAVAILABLE"));
    }

    @Test
    void listsStoredTransactionsNewestFirst() throws Exception {
        String firstId = storeTransaction("First", "2024-01-01", "10.00");
        String secondId = storeTransaction("Second", "2024-02-02", "20.00");

        // Other tests share this context's DB, so assert ordering of the two just-stored
        // rows (they are the newest) rather than the absolute list size.
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[1].id").value(firstId));
    }

    @Test
    void totalCountHeaderReflectsAllStoredEvenWhenListIsCapped() throws Exception {
        for (int i = 0; i < 5; i++) {
            storeTransaction("Cap" + i, "2024-01-0" + (i + 1), "1.00");
        }
        // limit=1 truncates the body to one row, but the header still reports the true total.
        String header = mockMvc.perform(get("/api/transactions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getHeader("X-Total-Count");
        org.assertj.core.api.Assertions.assertThat(Integer.parseInt(header))
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void limitParamCapsTheNumberOfReturnedTransactions() throws Exception {
        storeTransaction("L1", "2024-01-01", "1.00");
        storeTransaction("L2", "2024-01-02", "2.00");
        storeTransaction("L3", "2024-01-03", "3.00");

        mockMvc.perform(get("/api/transactions").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listsCurrenciesUsdFirstThenIsoLabelled() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/rates"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"data":[
                              {"country_currency_desc":"Canada-Dollar","record_date":"2023-12-31"},
                              {"country_currency_desc":"Euro Zone-Euro","record_date":"2023-12-31"},
                              {"country_currency_desc":"Canada-Dollar","record_date":"2023-09-30"}
                            ]}""")));

        mockMvc.perform(get("/api/currencies"))
                .andExpect(status().isOk())
                // USD passthrough option is always first.
                .andExpect(jsonPath("$[0].value").value("USD"))
                .andExpect(jsonPath("$[0].label").value("United States-Dollar (USD)"))
                // Treasury entries are ISO-labelled (CAD known; '-Euro' rule -> EUR).
                .andExpect(jsonPath("$[1].value").value("Canada-Dollar"))
                .andExpect(jsonPath("$[1].label").value("Canada-Dollar (CAD)"))
                .andExpect(jsonPath("$[2].label").value("Euro Zone-Euro (EUR)"));
    }

    @Test
    void convertsUsdToUsdWithoutCallingTreasury() throws Exception {
        String id = storeTransaction("Domestic", "2024-03-15", "100.005");
        // No WireMock stub on purpose: USD must not hit the Treasury API.

        mockMvc.perform(get("/api/transactions/" + id).param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCurrency").value("USD"))
                .andExpect(jsonPath("$.isoCode").value("USD"))
                .andExpect(jsonPath("$.exchangeRate").value(1.00))
                // 100.005 stored -> 100.01 (HALF_UP); USD passthrough keeps it unchanged.
                .andExpect(jsonPath("$.originalAmountUsd").value(100.01))
                .andExpect(jsonPath("$.convertedAmount").value(100.01));
    }

    @Test
    void returns404ForUnknownId() throws Exception {
        stubTreasury(rateBody("Canada-Dollar", "1.3", "2023-06-30"), 200);
        mockMvc.perform(get("/api/transactions/" + java.util.UUID.randomUUID())
                        .param("currency", "Canada-Dollar"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns400WhenCurrencyParamMissing() throws Exception {
        mockMvc.perform(get("/api/transactions/" + java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_PARAMETER"));
    }

    @Test
    void returns400ForMalformedCurrencyAndDoesNotCallTreasury() throws Exception {
        String id = storeTransaction("Inject", "2024-03-15", "10.00");
        // No WireMock stub: a separator-bearing currency must be rejected before any call.
        mockMvc.perform(get("/api/transactions/" + id)
                        .param("currency", "Canada-Dollar,record_date:gte:2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_PARAMETER"));
    }

    @Test
    void returns400WhenIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/transactions/not-a-uuid").param("currency", "Canada-Dollar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_PARAMETER"));
    }

    @Test
    void returns404ForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/this/does/not/exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returns503WhenTreasuryApiFails() throws Exception {
        String id = storeTransaction("Server down", "2023-07-15", "100.00");
        stubTreasury("upstream boom", 500);

        mockMvc.perform(get("/api/transactions/" + id).param("currency", "Canada-Dollar"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TREASURY_UNAVAILABLE"));
    }

    // ---- Idempotency ----

    @Test
    void sameIdempotencyKeyAndBodyReturnsSameTransaction() throws Exception {
        String body = """
            {"description":"Idem","transactionDate":"2024-01-02","purchaseAmount":9.99}""";
        String first = mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(first, "$.id"))
                .isEqualTo(JsonPath.read(second, "$.id"));
    }

    @Test
    void sameIdempotencyKeyDifferentBodyConflicts() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", "dup")
                        .contentType("application/json")
                        .content("""
                            {"description":"One","transactionDate":"2024-01-02",
                             "purchaseAmount":1.00}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", "dup")
                        .contentType("application/json")
                        .content("""
                            {"description":"Two","transactionDate":"2024-01-02",
                             "purchaseAmount":2.00}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }
}
