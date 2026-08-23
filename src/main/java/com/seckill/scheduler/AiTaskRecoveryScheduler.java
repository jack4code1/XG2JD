package com.seckill.scheduler;

import com.seckill.model.AiTask;
import com.seckill.repository.AiTaskRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.service.AiExecutionService;
import com.seckill.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Marks abandoned tool executions for manual review instead of replaying writes blindly. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskRecoveryScheduler {
    private final AiTaskRepository taskRepository;
    private final MerchantRepository merchantRepository;
    private final NotificationService notificationService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 60_000)
    public void markAbandonedExecutions() {
        var lock = redissonClient.getLock("lock:ai-task-recovery");
        if (!lock.tryLock()) return;
        try {
            for (AiTask task : taskRepository.findByStatusAndExecutingAtBefore(
                    AiExecutionService.EXECUTING, LocalDateTime.now().minusMinutes(10))) {
                task.setStatus("RECOVERY_REQUIRED");
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.save(task);
                merchantRepository.findById(task.getMerchantId()).ifPresent(merchant ->
                        notificationService.notify(merchant.getUserId(), "AI_TASK_RECOVERY_REQUIRED",
                                "AI 任务需要人工复核", "任务 " + task.getTaskNo() + " 执行超时，已停止自动重试。"));
                log.error("AI task marked for manual recovery: taskNo={}", task.getTaskNo());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
