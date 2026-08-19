package com.seckill.repository;

import com.seckill.model.AiAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiActionRepository extends JpaRepository<AiAction, Long> {
    List<AiAction> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
