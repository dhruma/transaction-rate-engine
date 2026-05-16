package com.wex.currency.service;

import com.wex.currency.domain.PurchaseTransaction;
import com.wex.currency.dto.ConvertedTransactionResponse;
import com.wex.currency.dto.CreateTransactionRequest;
import com.wex.currency.dto.TransactionResponse;
import com.wex.currency.exception.TransactionNotFoundException;
import com.wex.currency.repository.PurchaseTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates storing and retrieving purchase transactions (Requirements #1 and #2).
 *
 * <p>Field-level validation (length, positivity, date format) is enforced by Bean Validation on
 * {@link CreateTransactionRequest}; this service owns the remaining business rules: rounding the
 * amount to the nearest cent, id assignment, and idempotent replay.
 */
@Service
public class TransactionService {

    private final PurchaseTransactionRepository transactionRepository;
    private final CurrencyConversionService currencyConversionService;
    private final IdempotencyService idempotencyService;

    public TransactionService(PurchaseTransactionRepository transactionRepository,
                              CurrencyConversionService currencyConversionService,
                              IdempotencyService idempotencyService) {
        this.transactionRepository = transactionRepository;
        this.currencyConversionService = currencyConversionService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Stores a purchase transaction (Requirement #1) and returns the persisted record.
     *
     * <p>If {@code idempotencyKey} is supplied and was seen before with the same body, the
     * original transaction is returned instead of creating a duplicate.
     *
     * @param idempotencyKey optional {@code Idempotency-Key} header value; may be {@code null}/blank
     */
    @Transactional
    public PurchaseTransaction store(CreateTransactionRequest request, String idempotencyKey) {
        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();

        if (idempotent) {
            Optional<UUID> existing =
                    idempotencyService.findExistingTransaction(idempotencyKey, request);
            if (existing.isPresent()) {
                return transactionRepository.findById(existing.get())
                        .orElseThrow(() -> new TransactionNotFoundException(existing.get()));
            }
        }

        // "rounded to the nearest cent": scale 2, HALF_UP. Done once, on the way in, so the
        // stored amount is the canonical value used for every later conversion.
        BigDecimal amount = request.purchaseAmount().setScale(2, RoundingMode.HALF_UP);
        UUID id = UUID.randomUUID();

        // Claim the idempotency key BEFORE creating the transaction. The key's primary-key
        // constraint is the integrity guard: if a concurrent request already claimed it, the
        // insert fails (DataIntegrityViolationException → 409) and no duplicate transaction is
        // created, because we haven't created it yet. This is insert-or-return, not
        // check-then-write — correct without app-level locking.
        if (idempotent) {
            idempotencyService.register(idempotencyKey, id, request);
        }

        PurchaseTransaction tx = new PurchaseTransaction(
                id,
                request.description(),
                request.transactionDate(),
                amount,
                Instant.now());
        return transactionRepository.save(tx);
    }

    /**
     * Retrieves a stored transaction converted to {@code currency} (Requirement #2).
     *
     * <p>Deliberately NOT {@code @Transactional}: the load is a single short repository call
     * (transactional on its own), and the entity has no lazy associations, so the outbound
     * Treasury HTTP call in {@code convert(...)} runs <em>outside</em> any DB transaction.
     * Holding a pooled connection for the duration of an external API call would tie pool
     * lifetime to Treasury latency and risk pool exhaustion under load.
     */
    public ConvertedTransactionResponse retrieveConverted(UUID id, String currency) {
        PurchaseTransaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException(id));
        return currencyConversionService.convert(tx, currency);
    }

    /** Hard upper bound on the recent-transactions list, regardless of requested size. */
    private static final int MAX_LIST_LIMIT = 100;

    /**
     * Lists the most recent stored transactions, newest first. Not a brief requirement, but
     * it lets the UI offer click-to-select retrieval instead of copying ids by hand. The
     * result is capped so the list (and payload) stays bounded as data grows.
     *
     * @param limit requested size; clamped to 1..{@value #MAX_LIST_LIMIT}
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> listRecent(int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return transactionRepository
                .findByOrderByCreatedAtDesc(PageRequest.of(0, capped)).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    /** Total number of stored transactions — lets the UI show "showing N of total". */
    @Transactional(readOnly = true)
    public long totalCount() {
        return transactionRepository.count();
    }
}
