package com.vcc.gateway.repository;

import com.vcc.gateway.entity.AdminAuditLogEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;

@Repository
public interface AdminAuditLogRepository extends ReactiveCrudRepository<AdminAuditLogEntity, Long> {

    Flux<AdminAuditLogEntity> findByActor(String actor);

    Flux<AdminAuditLogEntity> findByAction(String action);

    Flux<AdminAuditLogEntity> findByTargetTypeAndTargetId(String targetType, String targetId);

    @Query("SELECT * FROM admin_audit_log WHERE created_at >= :from AND created_at <= :to ORDER BY created_at DESC")
    Flux<AdminAuditLogEntity> findByTimeRange(Instant from, Instant to);

    @Query("SELECT * FROM admin_audit_log ORDER BY created_at DESC LIMIT :limit")
    Flux<AdminAuditLogEntity> findRecent(int limit);
}
