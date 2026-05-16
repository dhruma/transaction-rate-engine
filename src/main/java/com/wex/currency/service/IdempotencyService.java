package com.wex.currency.service;

import com.wex.currency.domain.IdempotencyRecord;
import com.wex.currency.dto.CreateTransactionRequest;
import com.wex.currency.exception.IdempotencyConflictException;
import com.wex.currency.repository.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Implements optional request idempotency for the store-transaction endpoint.
 *
 * <p>Contract:
 * <ul>
 *   <li>Same key + identical body  → the originally created transaction id is replayed.</li>
 *   <li>Same key + different body  → {@link IdempotencyConflictException} (HTTP 422). Returning
 *       the original record would misrepresent the new request, hiding a likely client bug.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * If the key was used before, returns the original transaction id (replay). Empty means
     * this is a new key and the caller should create + {@link #register} a transaction.
     *
     * @throws IdempotencyConflictException if the key was used with a different request body
     */
    public Optional<UUID> findExistingTransaction(String idempotencyKey, CreateTransactionRequest request) {
        return repository.findById(idempotencyKey).map(record -> {
            if (!record.getRequestHash().equals(hash(request))) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key '" + idempotencyKey
                                + "' was already used with a different request body");
            }
            return record.getTransactionId();
        });
    }

    /**
     * Claims the key by inserting the key→transaction mapping. {@code saveAndFlush} forces the
     * INSERT (and the primary-key uniqueness check) to happen now, so a concurrent duplicate
     * surfaces immediately as a {@code DataIntegrityViolationException} rather than silently at
     * commit. The PK constraint — not an app-level check-then-write — is the dedup guarantee.
     */
    public void register(String idempotencyKey, UUID transactionId, CreateTransactionRequest request) {
        repository.saveAndFlush(new IdempotencyRecord(
                idempotencyKey, transactionId, hash(request), Instant.now()));
    }

    /** Stable SHA-256 over the request fields, used to detect body mismatches on replay. */
    private String hash(CreateTransactionRequest request) {
        // stripTrailingZeros so 10 and 10.00 hash identically — they are the same purchase.
        String canonical = request.description() + "|"
                + request.transactionDate() + "|"
                + request.purchaseAmount().stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
