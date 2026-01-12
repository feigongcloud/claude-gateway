package com.vcc.gateway.repository;

import com.vcc.gateway.entity.TenantEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface TenantRepository extends ReactiveCrudRepository<TenantEntity, String> {

    Flux<TenantEntity> findByStatus(String status);

    @Query("SELECT * FROM tenant WHERE status = 'active'")
    Flux<TenantEntity> findAllActive();

    Mono<Boolean> existsByTenantId(String tenantId);
}
