-- Execute once in an approved release window after backing up affected rows.
-- Coupon claims deliberately retain CREATED; only purchasable product orders move.
UPDATE t_order
SET status = 'PENDING_PAYMENT', version = version + 1
WHERE order_type = 'PRODUCT_PURCHASE' AND status = 'CREATED';
