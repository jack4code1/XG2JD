package com.seckill.repository;

import com.seckill.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNo(String orderNo);
    List<Order> findByUserIdAndCouponId(Long userId, Long couponId);
    List<Order> findByCouponIdIn(Collection<Long> couponIds);
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Order> findByUserIdAndOrderType(Long userId, String orderType);
    List<Order> findByStatusAndCreatedAtBefore(String status, LocalDateTime createdAt);
    boolean existsByUserIdAndCouponIdAndOrderType(Long userId, Long couponId, String orderType);
    boolean existsByUserIdAndCouponIdAndOrderTypeAndStatusIn(Long userId, Long couponId, String orderType, List<String> statuses);

    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.status = 'EXPIRED', o.version = o.version + 1 " +
           "WHERE o.status = 'PENDING_PAYMENT' AND o.orderType = 'PRODUCT_PURCHASE' AND o.createdAt < ?1")
    int expireOrders(LocalDateTime expireTime);
}
