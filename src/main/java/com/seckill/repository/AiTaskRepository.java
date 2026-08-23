package com.seckill.repository;

import com.seckill.model.AiTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTask, Long> {
    Optional<AiTask> findByTaskNoAndMerchantId(String taskNo, Long merchantId);
    List<AiTask> findTop20ByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /** Atomically claim a pending task. Safe across multiple application instances. */
    @Modifying
    @Transactional
    @Query("update AiTask t set t.status = :executing, t.confirmedAt = :confirmedAt, t.executingAt = :confirmedAt " +
            "where t.taskNo = :taskNo and t.merchantId = :merchantId and t.status = :waiting")
    int claimWaitingForExecution(@Param("taskNo") String taskNo,
                                 @Param("merchantId") Long merchantId,
                                 @Param("waiting") String waiting,
                                 @Param("executing") String executing,
                                 @Param("confirmedAt") LocalDateTime confirmedAt);

    List<AiTask> findByStatusAndExecutingAtBefore(String status, LocalDateTime executingAt);
}
