package com.wex.currency.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.wex.currency.repository.PurchaseTransactionRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the headline idempotency guarantee under real concurrency: two simultaneous POSTs
 * with the same {@code Idempotency-Key} and body race against a real running server + H2.
 *
 * <p>This backs the claim made in {@code TransactionService}/{@code IdempotencyService} that
 * the primary-key constraint — not an app-level check-then-write — is the dedup guard. The
 * invariant asserted is the one that actually matters: <strong>at most one
 * {@code PurchaseTransaction} row is ever persisted</strong>, and the losing request fails
 * cleanly (409) rather than 5xx or a duplicate. Runs on a real port (not MockMvc, which is
 * single-threaded per call) so the two requests genuinely overlap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyConcurrencyIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PurchaseTransactionRepository transactionRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Store path does not call Treasury, but the context still needs the property.
        registry.add("treasury.base-url", () -> "http://localhost:0/rates");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:concurrencydb;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Test
    void twoConcurrentRequestsWithSameKeyPersistExactlyOneTransaction() throws Exception {
        String key = "concurrent-key";
        String description = "ConcurrentIdem";
        String body = """
            {"description":"%s","transactionDate":"2024-01-02","purchaseAmount":12.34}"""
                .formatted(description);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        CyclicBarrier startLine = new CyclicBarrier(2);
        Callable<ResponseEntity<String>> fire = () -> {
            startLine.await(); // release both threads at the same instant
            return rest.postForEntity("/api/transactions", request, String.class);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> a = pool.submit(fire);
            Future<ResponseEntity<String>> b = pool.submit(fire);
            HttpStatus s1 = HttpStatus.valueOf(a.get().getStatusCode().value());
            HttpStatus s2 = HttpStatus.valueOf(b.get().getStatusCode().value());

            // The core invariant: a concurrent duplicate never creates a second row.
            List<?> persisted = transactionRepository.findAll().stream()
                    .filter(t -> description.equals(t.getDescription()))
                    .toList();
            assertThat(persisted).hasSize(1);

            // Neither request 5xx'd. At least one created (201); any loser is a clean
            // 409 DUPLICATE_REQUEST (race at the PK) or a 201 replay of the same record.
            assertThat(s1.is5xxServerError()).isFalse();
            assertThat(s2.is5xxServerError()).isFalse();
            assertThat(List.of(s1, s2)).contains(HttpStatus.CREATED);
            assertThat(s1).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(s2).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }
}
