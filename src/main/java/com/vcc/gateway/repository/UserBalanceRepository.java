package com.vcc.gateway.repository;

import com.vcc.gateway.entity.UserBalanceEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Repository for user balance management.
 * Provides atomic operations to prevent race conditions.
 */
@Repository
public interface UserBalanceRepository extends ReactiveCrudRepository<UserBalanceEntity, Long> {

    /**
     * Find user by uid (unique identifier).
     */
    Mono<UserBalanceEntity> findByUid(String uid);

    /**
     * Atomically deduct amount from balance using optimistic locking.
     * Returns number of affected rows (1 if success, 0 if balance changed or insufficient).
     *
     * This method combines three operations in ONE atomic WHERE clause:
     * 1. Check balance >= amount (sufficient funds)
     * 2. Check balance = expectedBalance (optimistic lock)
     * 3. Deduct balance = balance - amount
     *
     * CRITICAL for preventing race conditions in concurrent requests.
     * If this returns 0, the caller should retry with the new balance.
     *
     * @param uid User unique ID
     * @param amount Amount to deduct
     * @param expectedBalance Expected current balance (optimistic lock)
     * @return Number of rows affected (1 = success, 0 = conflict or insufficient)
     */
    @Modifying
    @Query("UPDATE users SET balance = balance - :amount " +
           "WHERE uid = :uid AND balance >= :amount AND balance = :expectedBalance")
    Mono<Integer> deductBalanceAtomic(String uid, BigDecimal amount, BigDecimal expectedBalance);

    /**
     * Atomically add amount to balance (for refunds).
     * No optimistic locking needed as refunds can't cause negative balance.
     *
     * @param uid User unique ID
     * @param amount Amount to add (refund)
     * @return Number of rows affected (1 = success, 0 = user not found)
     */
    @Modifying
    @Query("UPDATE users SET balance = balance + :amount WHERE uid = :uid")
    Mono<Integer> addBalanceAtomic(String uid, BigDecimal amount);

    /**
     * Check if user exists and is active (status=1) and not frozen (is_frozen=0).
     * Used for pre-request validation.
     *
     * @param uid User unique ID
     * @return Count (1 if active, 0 if not found/disabled/frozen)
     */
    @Query("SELECT COUNT(*) FROM users WHERE uid = :uid AND status = 1 AND is_frozen = 0")
    Mono<Long> countActiveByUid(String uid);
}
