package com.seckill.repository;

import com.seckill.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** The order number is the consumer idempotency key; MySQL performs this gate atomically. */
    @Modifying
    @Query(value = "INSERT INTO t_order (order_no, user_id, coupon_id, order_type, status, amount, " +
            "original_amount, discount_amount, version, created_at, updated_at) " +
            "VALUES (:orderNo, :userId, :couponId, 'COUPON_CLAIM', 'CREATED', :amount, :amount, 0, 0, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE order_no = VALUES(order_no)", nativeQuery = true)
    int insertCouponClaimIfAbsent(@Param("orderNo") String orderNo, @Param("userId") Long userId,
                                  @Param("couponId") Long couponId, @Param("amount") java.math.BigDecimal amount);

    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.status = 'EXPIRED', o.version = o.version + 1 " +
           "WHERE o.status = 'PENDING_PAYMENT' AND o.orderType = 'PRODUCT_PURCHASE' AND o.createdAt < ?1")
    int expireOrders(LocalDateTime expireTime);
}
