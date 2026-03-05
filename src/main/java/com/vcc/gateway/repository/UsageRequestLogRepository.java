package com.vcc.gateway.repository;

import com.vcc.gateway.entity.UsageRequestLogEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface UsageRequestLogRepository extends ReactiveCrudRepository<UsageRequestLogEntity, String> {

    Flux<UsageRequestLogEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Query("SELECT * FROM usage_request_log WHERE tenant_id = :tenantId " +
           "AND created_at >= :from AND created_at <= :to ORDER BY created_at DESC")
    Flux<UsageRequestLogEntity> findByTenantIdAndTimeRange(String tenantId, Instant from, Instant to);

    @Query("SELECT * FROM usage_request_log WHERE tenant_id = :tenantId " +
           "ORDER BY created_at DESC LIMIT :limit")
    Flux<UsageRequestLogEntity> findRecentByTenant(String tenantId, int limit);

    @Query("SELECT COUNT(*) FROM usage_request_log WHERE tenant_id = :tenantId " +
           "AND created_at >= :from")
    Mono<Long> countByTenantSince(String tenantId, Instant from);
}
