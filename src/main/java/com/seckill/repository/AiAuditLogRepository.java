package com.seckill.repository;

import com.seckill.model.AiAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAuditLogRepository extends JpaRepository<AiAuditLog, Long> {
    List<AiAuditLog> findTop20ByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
