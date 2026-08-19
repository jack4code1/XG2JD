package com.seckill.repository;

import com.seckill.model.AiTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTask, Long> {
    Optional<AiTask> findByTaskNoAndMerchantId(String taskNo, Long merchantId);
    List<AiTask> findTop20ByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
