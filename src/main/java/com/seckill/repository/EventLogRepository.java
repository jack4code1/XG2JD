package com.seckill.repository;

import com.seckill.model.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    List<EventLog> findByStatusAndNextRetryAtBefore(Integer status, LocalDateTime time);
    List<EventLog> findByAggregateIdIn(Collection<String> aggregateIds);
}
