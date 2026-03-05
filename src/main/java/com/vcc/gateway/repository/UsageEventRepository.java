package com.vcc.gateway.repository;

import com.vcc.gateway.entity.UsageEventEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@Repository
public interface UsageEventRepository extends ReactiveCrudRepository<UsageEventEntity, Long> {

    Mono<UsageEventEntity> findByRequestId(String requestId);

    Flux<UsageEventEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Query("SELECT * FROM usage_event WHERE tenant_id = :tenantId " +
           "AND created_at >= :from AND created_at <= :to ORDER BY created_at DESC")
    Flux<UsageEventEntity> findByTenantIdAndTimeRange(String tenantId, Instant from, Instant to);

    @Query("SELECT SUM(total_tokens) FROM usage_event WHERE tenant_id = :tenantId " +
           "AND created_at >= :from")
    Mono<Long> sumTokensByTenantSince(String tenantId, Instant from);

    @Query("SELECT SUM(cost_usd) FROM usage_event WHERE tenant_id = :tenantId " +
           "AND created_at >= :from")
    Mono<BigDecimal> sumCostByTenantSince(String tenantId, Instant from);
}
