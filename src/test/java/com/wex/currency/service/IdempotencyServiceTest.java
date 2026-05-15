package com.wex.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.wex.currency.domain.IdempotencyRecord;
import com.wex.currency.dto.CreateTransactionRequest;
import com.wex.currency.exception.IdempotencyConflictException;
import com.wex.currency.repository.IdempotencyRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    private IdempotencyService service;

    private final CreateTransactionRequest request = new CreateTransactionRequest(
            "Coffee", LocalDate.of(2024, 1, 2), new BigDecimal("4.50"));

    @BeforeEach
    void setUp() {
        // Stateful in-memory stand-in so register() + findExistingTransaction() interact
        // through the real hashing logic rather than pre-baked hashes.
        Map<String, IdempotencyRecord> store = new HashMap<>();
        lenient().when(repository.save(any())).thenAnswer(i -> {
            IdempotencyRecord r = i.getArgument(0);
            store.put(r.getIdempotencyKey(), r);
            return r;
        });
        lenient().when(repository.findById(any())).thenAnswer(i ->
                Optional.ofNullable(store.get(i.getArgument(0))));
        service = new IdempotencyService(repository);
    }

    @Test
    void newKeyReturnsEmpty() {
        assertThat(service.findExistingTransaction("k1", request)).isEmpty();
    }

    @Test
    void sameKeySameBodyReplaysOriginalId() {
        UUID original = UUID.randomUUID();
        service.register("k1", original, request);

        // 4.50 vs 4.5 must hash identically (stripTrailingZeros).
        var equivalent = new CreateTransactionRequest(
                "Coffee", LocalDate.of(2024, 1, 2), new BigDecimal("4.5"));
        assertThat(service.findExistingTransaction("k1", equivalent)).contains(original);
    }

    @Test
    void sameKeyDifferentBodyConflicts() {
        service.register("k1", UUID.randomUUID(), request);

        var different = new CreateTransactionRequest(
                "Tea", LocalDate.of(2024, 1, 2), new BigDecimal("4.50"));
        assertThatThrownBy(() -> service.findExistingTransaction("k1", different))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request body");
    }
}
