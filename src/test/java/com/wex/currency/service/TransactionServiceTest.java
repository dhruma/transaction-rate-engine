package com.wex.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wex.currency.domain.PurchaseTransaction;
import com.wex.currency.dto.CreateTransactionRequest;
import com.wex.currency.exception.TransactionNotFoundException;
import com.wex.currency.repository.PurchaseTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private PurchaseTransactionRepository transactionRepository;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private IdempotencyService idempotencyService;

    private TransactionService service() {
        return new TransactionService(
                transactionRepository, currencyConversionService, idempotencyService);
    }

    @Test
    void storesAmountRoundedToNearestCentHalfUp() {
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var request = new CreateTransactionRequest(
                "Lunch", LocalDate.of(2024, 3, 1), new BigDecimal("12.345"));

        PurchaseTransaction stored = service().store(request, null);

        // 12.345 -> HALF_UP -> 12.35, persisted at scale 2.
        assertThat(stored.getPurchaseAmountUsd()).isEqualByComparingTo("12.35");
        assertThat(stored.getPurchaseAmountUsd().scale()).isEqualTo(2);
        assertThat(stored.getId()).isNotNull();
    }

    @Test
    void replaysExistingTransactionWhenIdempotencyKeyAlreadyUsed() {
        UUID existingId = UUID.randomUUID();
        var request = new CreateTransactionRequest(
                "Lunch", LocalDate.of(2024, 3, 1), new BigDecimal("12.34"));
        when(idempotencyService.findExistingTransaction("key-1", request))
                .thenReturn(Optional.of(existingId));
        PurchaseTransaction existing = new PurchaseTransaction(
                existingId, "Lunch", request.transactionDate(),
                new BigDecimal("12.34"), java.time.Instant.now());
        when(transactionRepository.findById(existingId)).thenReturn(Optional.of(existing));

        PurchaseTransaction result = service().store(request, "key-1");

        assertThat(result.getId()).isEqualTo(existingId);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void losingTheIdempotencyKeyRaceCreatesNoTransaction() {
        var request = new CreateTransactionRequest(
                "Race", LocalDate.of(2024, 3, 1), new BigDecimal("5.00"));
        when(idempotencyService.findExistingTransaction("dup", request))
                .thenReturn(Optional.empty());
        // A concurrent request already claimed the key: the PK insert loses the race.
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataIntegrityViolationException("dup pk"))
                .when(idempotencyService).register(eq("dup"), any(), eq(request));

        assertThatThrownBy(() -> service().store(request, "dup"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // Key claimed before the transaction is created -> no duplicate row persisted.
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void retrieveConvertedThrowsNotFoundForUnknownId() {
        UUID unknown = UUID.randomUUID();
        when(transactionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retrieveConverted(unknown, "Canada-Dollar"))
                .isInstanceOf(TransactionNotFoundException.class);
        verifyNoInteractions(currencyConversionService);
    }
}
