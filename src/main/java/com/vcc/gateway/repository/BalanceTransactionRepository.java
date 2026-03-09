package com.vcc.gateway.repository;

import com.vcc.gateway.entity.BalanceTransactionEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for balance transaction audit trail.
 * Records all balance operations for reconciliation and debugging.
 */
@Repository
public interface BalanceTransactionRepository extends ReactiveCrudRepository<BalanceTransactionEntity, Long> {

    /**
     * Find all transactions for a user, ordered by most recent first.
     *
     * @param uid User unique ID
     * @return Flux of transactions
     */
    Flux<BalanceTransactionEntity> findByUidOrderByCreatedAtDesc(String uid);

    /**
     * Find all transactions for a specific request.
     * Useful for debugging a complete request lifecycle (RESERVE → COMMIT/REFUND).
     *
     * @param requestId Request ID from usage_request_log
     * @return Flux of transactions for this request
     */
    Flux<BalanceTransactionEntity> findByRequestIdOrderByCreatedAtAsc(String requestId);

    /**
     * Find pending RESERVE transactions that are older than the specified time.
     * Used by reconciliation job to clean up orphaned reserves.
     *
     * @param before Find reserves created before this time
     * @return Flux of pending reserve transactions
     */
    @Query("SELECT * FROM balance_transaction " +
           "WHERE status = 'pending' AND transaction_type = 'RESERVE' AND created_at < :before")
    Flux<BalanceTransactionEntity> findPendingReservesBefore(LocalDateTime before);

    /**
     * Count transactions by type for monitoring.
     *
     * @param transactionType Type to count (RESERVE, COMMIT, REFUND, ADJUST)
     * @return Count of transactions
     */
    @Query("SELECT COUNT(*) FROM balance_transaction WHERE transaction_type = :transactionType")
    Mono<Long> countByTransactionType(String transactionType);

    /**
     * Count transactions by status for monitoring.
     *
     * @param status Status to count (pending, completed, failed, rolled_back)
     * @return Count of transactions
     */
    @Query("SELECT COUNT(*) FROM balance_transaction WHERE status = :status")
    Mono<Long> countByStatus(String status);
}
