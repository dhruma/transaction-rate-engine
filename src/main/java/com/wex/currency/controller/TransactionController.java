package com.wex.currency.controller;

import com.wex.currency.client.TreasuryRatesClient;
import com.wex.currency.dto.ConvertedTransactionResponse;
import com.wex.currency.dto.CreateTransactionRequest;
import com.wex.currency.dto.TransactionResponse;
import com.wex.currency.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for storing and retrieving purchase transactions. */
@RestController
@RequestMapping("/api")
@Tag(name = "Transactions", description = "Store and retrieve purchase transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final TreasuryRatesClient treasuryRatesClient;

    public TransactionController(TransactionService transactionService,
                                 TreasuryRatesClient treasuryRatesClient) {
        this.transactionService = transactionService;
        this.treasuryRatesClient = treasuryRatesClient;
    }

    /** Requirement #1: store a purchase transaction; returns the generated identifier. */
    @PostMapping("/transactions")
    @Operation(summary = "Store a purchase transaction")
    public ResponseEntity<TransactionResponse> store(
            @Valid @RequestBody CreateTransactionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        TransactionResponse body =
                TransactionResponse.from(transactionService.store(request, idempotencyKey));
        return ResponseEntity
                .created(URI.create("/api/transactions/" + body.id()))
                .body(body);
    }

    /**
     * Lists the most recent stored transactions, newest first (powers the UI's selectable
     * list). {@code limit} defaults to 20 and is capped server-side so the payload stays
     * bounded as data grows. The {@code X-Total-Count} response header carries the full
     * number of stored transactions so clients can show "showing N of total" — the cap is a
     * view limit, never a storage limit.
     */
    @GetMapping("/transactions")
    @Operation(summary = "List recent stored transactions (newest first)")
    public ResponseEntity<List<TransactionResponse>> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(transactionService.totalCount()))
                .body(transactionService.listRecent(limit));
    }

    /** Requirement #2: retrieve a stored transaction converted to the target currency. */
    @GetMapping("/transactions/{id}")
    @Operation(summary = "Retrieve a stored transaction converted to a target currency")
    public ConvertedTransactionResponse retrieve(
            @PathVariable UUID id,
            @RequestParam("currency") String currency) {
        return transactionService.retrieveConverted(id, currency);
    }

    /** Distinct Treasury currency identifiers — powers the UI dropdown. */
    @GetMapping("/currencies")
    @Operation(summary = "List currencies supported by the Treasury API")
    public List<String> currencies() {
        return treasuryRatesClient.listCurrencies();
    }
}
