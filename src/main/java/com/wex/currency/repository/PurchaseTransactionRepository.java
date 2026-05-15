package com.wex.currency.repository;

import com.wex.currency.domain.PurchaseTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseTransactionRepository extends JpaRepository<PurchaseTransaction, UUID> {

    /** Most-recently-stored first, capped by the caller — backs the UI's transaction list. */
    List<PurchaseTransaction> findByOrderByCreatedAtDesc(Pageable pageable);
}
