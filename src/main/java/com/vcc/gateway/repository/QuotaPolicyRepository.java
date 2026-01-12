package com.vcc.gateway.repository;

import com.vcc.gateway.entity.QuotaPolicyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface QuotaPolicyRepository extends ReactiveCrudRepository<QuotaPolicyEntity, String> {

    Mono<QuotaPolicyEntity> findByTenantId(String tenantId);
}
