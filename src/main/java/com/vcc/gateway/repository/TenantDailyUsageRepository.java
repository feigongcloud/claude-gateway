package com.vcc.gateway.repository;

import com.vcc.gateway.entity.TenantDailyUsageEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

@Repository
public interface TenantDailyUsageRepository extends ReactiveCrudRepository<TenantDailyUsageEntity, Long> {

    Mono<TenantDailyUsageEntity> findByTenantIdAndDay(String tenantId, LocalDate day);

    Flux<TenantDailyUsageEntity> findByTenantIdAndDayBetweenOrderByDayDesc(
            String tenantId, LocalDate from, LocalDate to);

    @Modifying
    @Query("INSERT INTO tenant_daily_usage " +
           "(tenant_id, day, request_count, success_count, error_count, " +
           "input_tokens, output_tokens, total_tokens, cost_usd) " +
           "VALUES (:tenantId, :day, :reqCount, :successCount, :errorCount, " +
           ":inputTokens, :outputTokens, :totalTokens, :costUsd) " +
           "ON DUPLICATE KEY UPDATE " +
           "request_count = request_count + :reqCount, " +
           "success_count = success_count + :successCount, " +
           "error_count = error_count + :errorCount, " +
           "input_tokens = input_tokens + :inputTokens, " +
           "output_tokens = output_tokens + :outputTokens, " +
           "total_tokens = total_tokens + :totalTokens, " +
           "cost_usd = cost_usd + :costUsd")
    Mono<Void> upsertDailyUsage(
            String tenantId, LocalDate day,
            int reqCount, int successCount, int errorCount,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal costUsd);
}
