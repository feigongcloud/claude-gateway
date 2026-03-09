package com.vcc.gateway.service;

import com.vcc.gateway.entity.BalanceTransactionEntity;
import com.vcc.gateway.entity.UserBalanceEntity;
import com.vcc.gateway.repository.BalanceTransactionRepository;
import com.vcc.gateway.repository.UserBalanceRepository;
import com.vcc.gateway.service.CostEstimator.CostEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Two-phase balance deduction service.
 *
 * Phase 1: Reserve (pre-request)
 *   - Check user status (frozen, disabled)
 *   - Check balance sufficiency
 *   - Atomically deduct (reserve) estimated cost
 *   - Return ReserveResult for settlement
 *
 * Phase 2a: Settle (post-request, success)
 *   - Calculate actual cost from real token usage
 *   - If actual < reserved: Refund difference
 *   - If actual == reserved: Mark as completed
 *   - If actual > reserved: Try to charge additional
 *
 * Phase 2b: Refund (post-request, failure)
 *   - Refund full reserved amount
 *   - Mark reserve as rolled_back
 *
 * Concurrency handling: Uses optimistic locking with retries
 */
@Service
public class BalanceService {
    private static final Logger log = LoggerFactory.getLogger(BalanceService.class);
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserBalanceRepository userBalanceRepository;
    private final BalanceTransactionRepository transactionRepository;

    public BalanceService(UserBalanceRepository userBalanceRepository,
                         BalanceTransactionRepository transactionRepository) {
        this.userBalanceRepository = userBalanceRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * PHASE 1: Check user status and reserve estimated cost.
     *
     * Checks (in order):
     * 1. User exists
     * 2. User not frozen (is_frozen = 0)
     * 3. User active (status = 1)
     * 4. Balance sufficient (balance >= estimated_cost)
     * 5. Atomically deduct (reserve) balance
     * 6. Create RESERVE transaction record
     *
     * Returns ReserveResult with transaction ID for Phase 2 settlement.
     *
     * Throws:
     * - 403 Forbidden: User not found / frozen / disabled
     * - 402 Payment Required: Insufficient balance
     * - RuntimeException: Retry after optimistic lock conflict
     */
    public Mono<ReserveResult> checkAndReserve(String uid, String requestId, CostEstimate estimate) {
        log.info("[BALANCE] Phase 1 - Reserve: uid={}, requestId={}, estimate=${}",
                 uid, requestId, estimate.totalCost());

        return userBalanceRepository.findByUid(uid)
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "User not found")))
            .flatMap(user -> {
                // Check frozen
                if (user.isFrozen()) {
                    String reason = user.getFreezeReason();
                    log.warn("[BALANCE] User frozen: uid={}, reason={}", uid, reason);
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Account frozen" + (reason != null ? ": " + reason : "")));
                }

                // Check status
                if (!user.isActive()) {
                    log.warn("[BALANCE] User inactive: uid={}, status={}", uid, user.getStatus());
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Account disabled"));
                }

                // Check balance
                if (user.hasInsufficientBalance(estimate.totalCost())) {
                    log.warn("[BALANCE] Insufficient balance: uid={}, balance={}, required={}",
                            uid, user.getBalance(), estimate.totalCost());
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        String.format("Insufficient balance: $%s required, $%s available",
                                     estimate.totalCost(), user.getBalance())));
                }

                // Atomically reserve balance
                return reserveBalance(user, requestId, estimate);
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
                .filter(throwable -> !(throwable instanceof ResponseStatusException))
                .doBeforeRetry(signal -> log.debug("[BALANCE] Retrying reserve due to conflict, attempt={}",
                                                   signal.totalRetries() + 1)));
    }

    /**
     * Atomically reserve balance and create transaction record.
     */
    private Mono<ReserveResult> reserveBalance(UserBalanceEntity user, String requestId,
                                               CostEstimate estimate) {
        BigDecimal balanceBefore = user.getBalance();
        BigDecimal amount = estimate.totalCost();

        return userBalanceRepository.deductBalanceAtomic(user.getUid(), amount, balanceBefore)
            .flatMap(affectedRows -> {
                if (affectedRows == 0) {
                    // Balance changed between read and update (race condition)
                    log.warn("[BALANCE] Atomic deduct failed (conflict): uid={}", user.getUid());
                    return Mono.error(new RuntimeException("Balance conflict, retrying"));
                }

                // Success - create transaction record
                BalanceTransactionEntity transaction = new BalanceTransactionEntity();
                transaction.setUid(user.getUid());
                transaction.setRequestId(requestId);
                transaction.setTransactionType("RESERVE");
                transaction.setAmount(amount.negate());  // Negative for deduction
                transaction.setBalanceBefore(balanceBefore);
                transaction.setBalanceAfter(balanceBefore.subtract(amount));
                transaction.setEstimatedCost(amount);
                transaction.setModel(estimate.model());
                transaction.setTokensEstimated(estimate.totalTokens());
                transaction.setStatus("pending");
                transaction.setCreatedAt(nowBeijing());

                return transactionRepository.save(transaction)
                    .map(saved -> {
                        log.info("[BALANCE] ✓ Reserved ${} for uid={}, txId={}",
                                amount, user.getUid(), saved.getTransactionId());
                        return new ReserveResult(
                            saved.getTransactionId(),
                            user.getUid(),
                            amount,
                            balanceBefore,
                            balanceBefore.subtract(amount)
                        );
                    });
            });
    }

    /**
     * PHASE 2a: Settle actual cost after successful request.
     *
     * Three scenarios:
     * 1. actual < reserved: Refund excess to user
     * 2. actual == reserved: Mark reserve as completed
     * 3. actual > reserved: Try to charge additional amount (rare edge case)
     *
     * This method uses fire-and-forget pattern - failures are logged but don't
     * block the response (which is already sent to client).
     */
    public Mono<Void> settleActualCost(String uid, Long reserveTransactionId,
                                      BigDecimal reservedCost, BigDecimal actualCost,
                                      int actualTokens, String model) {
        log.info("[BALANCE] Phase 2 - Settle: uid={}, reserved=${}, actual=${}",
                 uid, reservedCost, actualCost);

        BigDecimal difference = reservedCost.subtract(actualCost);

        if (difference.compareTo(BigDecimal.ZERO) > 0) {
            // Case 1: Refund excess
            log.info("[BALANCE] Refunding excess: uid={}, refund=${}", uid, difference);
            return refundExcess(uid, reserveTransactionId, reservedCost, actualCost,
                               difference, actualTokens, model);
        } else if (difference.compareTo(BigDecimal.ZERO) < 0) {
            // Case 3: Charge additional (rare - estimation was too low)
            BigDecimal additional = difference.negate();
            log.warn("[BALANCE] Actual cost exceeded estimate: uid={}, additional=${}",
                    uid, additional);
            return chargeAdditional(uid, reserveTransactionId, reservedCost, actualCost,
                                   additional, actualTokens, model);
        } else {
            // Case 2: Exact match, just mark as completed
            return markReserveCompleted(reserveTransactionId, actualCost, actualTokens);
        }
    }

    /**
     * Refund excess reserved amount back to user balance.
     */
    private Mono<Void> refundExcess(String uid, Long reserveTransactionId,
                                   BigDecimal reserved, BigDecimal actual,
                                   BigDecimal refundAmount, int actualTokens, String model) {
        return userBalanceRepository.addBalanceAtomic(uid, refundAmount)
            .flatMap(affectedRows -> {
                if (affectedRows == 0) {
                    log.error("[BALANCE] Failed to refund excess: uid={}", uid);
                    return Mono.empty();
                }

                // Get current balance for transaction record
                return userBalanceRepository.findByUid(uid)
                    .flatMap(user -> {
                        BalanceTransactionEntity refundTx = new BalanceTransactionEntity();
                        refundTx.setUid(uid);
                        refundTx.setRequestId(null);  // Could link to reserve tx if needed
                        refundTx.setTransactionType("REFUND");
                        refundTx.setAmount(refundAmount);  // Positive for refund
                        refundTx.setBalanceBefore(user.getBalance().subtract(refundAmount));
                        refundTx.setBalanceAfter(user.getBalance());
                        refundTx.setEstimatedCost(reserved);
                        refundTx.setActualCost(actual);
                        refundTx.setModel(model);
                        refundTx.setTokensActual(actualTokens);
                        refundTx.setStatus("completed");
                        refundTx.setCreatedAt(nowBeijing());

                        return transactionRepository.save(refundTx);
                    })
                    .then(markReserveCompleted(reserveTransactionId, actual, actualTokens));
            })
            .then();
    }

    /**
     * Charge additional amount if actual cost exceeded estimate (rare case).
     */
    private Mono<Void> chargeAdditional(String uid, Long reserveTransactionId,
                                       BigDecimal reserved, BigDecimal actual,
                                       BigDecimal additional, int actualTokens, String model) {
        return userBalanceRepository.findByUid(uid)
            .flatMap(user -> {
                BigDecimal balanceBefore = user.getBalance();

                // Attempt to charge additional
                return userBalanceRepository.deductBalanceAtomic(uid, additional, balanceBefore)
                    .flatMap(affectedRows -> {
                        if (affectedRows == 0) {
                            log.error("[BALANCE] Failed to charge additional: uid={}, amount=${}",
                                     uid, additional);
                            // Don't fail the request - just log the discrepancy
                            // Admin can reconcile later
                            return markReserveCompleted(reserveTransactionId, actual, actualTokens);
                        }

                        // Success - record additional charge
                        BalanceTransactionEntity adjustTx = new BalanceTransactionEntity();
                        adjustTx.setUid(uid);
                        adjustTx.setTransactionType("ADJUST");
                        adjustTx.setAmount(additional.negate());
                        adjustTx.setBalanceBefore(balanceBefore);
                        adjustTx.setBalanceAfter(balanceBefore.subtract(additional));
                        adjustTx.setEstimatedCost(reserved);
                        adjustTx.setActualCost(actual);
                        adjustTx.setModel(model);
                        adjustTx.setTokensActual(actualTokens);
                        adjustTx.setStatus("completed");
                        adjustTx.setCreatedAt(nowBeijing());

                        return transactionRepository.save(adjustTx)
                            .then(markReserveCompleted(reserveTransactionId, actual, actualTokens));
                    });
            })
            .then();
    }

    /**
     * Mark reserve transaction as completed with actual cost.
     */
    private Mono<Void> markReserveCompleted(Long transactionId, BigDecimal actualCost,
                                           int actualTokens) {
        return transactionRepository.findById(transactionId)
            .flatMap(tx -> {
                tx.setStatus("completed");
                tx.setActualCost(actualCost);
                tx.setTokensActual(actualTokens);
                return transactionRepository.save(tx);
            })
            .then();
    }

    /**
     * PHASE 2b: Refund full reserved amount on request failure.
     *
     * Called when the upstream request fails or returns an error.
     * Refunds the entire reserved amount and marks the reserve as rolled_back.
     */
    public Mono<Void> refundReserve(String uid, Long reserveTransactionId,
                                   BigDecimal reservedAmount) {
        log.info("[BALANCE] Phase 2 - Refund (failure): uid={}, amount=${}",
                 uid, reservedAmount);

        return userBalanceRepository.addBalanceAtomic(uid, reservedAmount)
            .flatMap(affectedRows -> {
                if (affectedRows == 0) {
                    log.error("[BALANCE] Failed to refund reserve: uid={}", uid);
                    return Mono.empty();
                }

                // Mark reserve as rolled back
                return transactionRepository.findById(reserveTransactionId)
                    .flatMap(tx -> {
                        tx.setStatus("rolled_back");
                        return transactionRepository.save(tx);
                    })
                    .then(createRefundTransaction(uid, reservedAmount, "Request failed"));
            })
            .then()
            .doOnSuccess(v -> log.info("[BALANCE] ✓ Refunded ${} to uid={}",
                                      reservedAmount, uid))
            .doOnError(e -> log.error("[BALANCE] ✗ Refund failed for uid={}: {}",
                                     uid, e.getMessage()));
    }

    /**
     * Create a refund transaction record for audit trail.
     */
    private Mono<Void> createRefundTransaction(String uid, BigDecimal amount, String reason) {
        return userBalanceRepository.findByUid(uid)
            .flatMap(user -> {
                BalanceTransactionEntity refundTx = new BalanceTransactionEntity();
                refundTx.setUid(uid);
                refundTx.setTransactionType("REFUND");
                refundTx.setAmount(amount);  // Positive for refund
                refundTx.setBalanceBefore(user.getBalance().subtract(amount));
                refundTx.setBalanceAfter(user.getBalance());
                refundTx.setStatus("completed");
                refundTx.setErrorMessage(reason);
                refundTx.setCreatedAt(nowBeijing());

                return transactionRepository.save(refundTx);
            })
            .then();
    }

    /**
     * Get current time in Beijing timezone.
     */
    private LocalDateTime nowBeijing() {
        return LocalDateTime.now(BEIJING_ZONE);
    }

    /**
     * Value object for Phase 1 reserve result.
     */
    public record ReserveResult(
        Long transactionId,
        String uid,
        BigDecimal reservedAmount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter
    ) {}
}
