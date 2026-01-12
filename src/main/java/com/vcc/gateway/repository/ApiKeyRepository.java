package com.vcc.gateway.repository;

import com.vcc.gateway.entity.ApiKeyEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, String> {

    @Query("SELECT * FROM api_key WHERE key_hash = :keyHash AND status = 'active'")
    Mono<ApiKeyEntity> findActiveByKeyHash(String keyHash);

    Flux<ApiKeyEntity> findByTenantIdAndStatus(String tenantId, String status);

    Flux<ApiKeyEntity> findByTenantId(String tenantId);

    @Modifying
    @Query("UPDATE api_key SET status = 'revoked', updated_at = NOW(3) WHERE key_id = :keyId")
    Mono<Integer> revokeByKeyId(String keyId);

    Mono<Boolean> existsByKeyHash(String keyHash);

    Mono<Long> countByTenantIdAndStatus(String tenantId, String status);
}
