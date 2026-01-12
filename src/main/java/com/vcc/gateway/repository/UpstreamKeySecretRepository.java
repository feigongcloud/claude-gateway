package com.vcc.gateway.repository;

import com.vcc.gateway.entity.UpstreamKeySecretEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface UpstreamKeySecretRepository extends ReactiveCrudRepository<UpstreamKeySecretEntity, String> {

    @Query("SELECT * FROM upstream_key_secret WHERE status = 'active' ORDER BY upstream_key_id")
    Flux<UpstreamKeySecretEntity> findAllActive();

    Flux<UpstreamKeySecretEntity> findByStatus(String status);

    Flux<UpstreamKeySecretEntity> findByKeyVersion(int keyVersion);
}
