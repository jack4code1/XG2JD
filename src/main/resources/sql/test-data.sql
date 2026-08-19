-- 本地测试数据：密码统一为 123456
USE seckill;

SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE event_log;
TRUNCATE TABLE ai_audit_log;
TRUNCATE TABLE reconciliation_snapshot;
TRUNCATE TABLE t_order;
TRUNCATE TABLE t_coupon;
TRUNCATE TABLE t_product;
TRUNCATE TABLE t_merchant;
TRUNCATE TABLE t_user;
SET FOREIGN_KEY_CHECKS=1;

INSERT INTO t_user (id, username, password, phone, role) VALUES
(1, 'merchant_food', '$2a$10$GGU7k1FQF5redzDUmIRH/.tCA0xjTfgf9.R5/WEzOe3YQBrgSSL5W', '13800000001', 'MERCHANT'),
(2, 'merchant_fashion', '$2a$10$GGU7k1FQF5redzDUmIRH/.tCA0xjTfgf9.R5/WEzOe3YQBrgSSL5W', '13800000002', 'MERCHANT'),
(3, 'test_user', '$2a$10$GGU7k1FQF5redzDUmIRH/.tCA0xjTfgf9.R5/WEzOe3YQBrgSSL5W', '13800000003', 'USER'),
(4, 'test_user_2', '$2a$10$GGU7k1FQF5redzDUmIRH/.tCA0xjTfgf9.R5/WEzOe3YQBrgSSL5W', '13800000004', 'USER'),
(5, 'test_user_3', '$2a$10$GGU7k1FQF5redzDUmIRH/.tCA0xjTfgf9.R5/WEzOe3YQBrgSSL5W', '13800000005', 'USER');

INSERT INTO t_merchant (id, user_id, shop_name, shop_desc, category) VALUES
(1, 1, '火焰小食铺', '现做热食与新客福利，适合测试商户独立数据。', '餐饮'),
(2, 2, '拾光衣橱', '精选日常服饰与限时优惠，适合测试多商户隔离。', '服饰');

INSERT INTO t_coupon (id, coupon_name, coupon_desc, merchant_id, total_stock, remain_stock,
                      start_time, end_time, per_user_max, status, version) VALUES
(1, '新客立减 20 元', '首次到店用户专享，限量发放。', 1, 500, 498,
 NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 72 HOUR, 2, 1, 0),
(2, '午间 9 折券', '工作日午餐时段可用。', 1, 300, 300,
 NOW() + INTERVAL 2 HOUR, NOW() + INTERVAL 26 HOUR, 1, 0, 0),
(3, '秋日穿搭 50 元券', '满 199 元可用，限量秒杀。', 2, 800, 760,
 NOW() - INTERVAL 2 HOUR, NOW() + INTERVAL 48 HOUR, 1, 1, 0),
(4, '双人同行券', '两人同行享专属优惠。', 2, 100, 0,
 NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY, 1, 2, 0);

UPDATE t_coupon SET discount_amount=20 WHERE id=1;
UPDATE t_coupon SET discount_amount=15 WHERE id=2;
UPDATE t_coupon SET discount_amount=50 WHERE id=3;
UPDATE t_coupon SET discount_amount=30 WHERE id=4;

INSERT INTO t_product (id, merchant_id, name, description, price, remain_stock, status) VALUES
(1, 1, '招牌牛肉饭', '现做热食，工作日午餐套餐。', 38.00, 80, 1),
(2, 1, '冰柠檬茶', '清爽解腻，适合搭配主食。', 12.00, 120, 1),
(3, 2, '基础款针织衫', '柔软亲肤，秋季日常百搭。', 129.00, 35, 1),
(4, 2, '通勤帆布包', '轻便耐用，日常通勤容量充足。', 89.00, 50, 1);

ALTER TABLE t_user AUTO_INCREMENT=6;
ALTER TABLE t_merchant AUTO_INCREMENT=3;
ALTER TABLE t_coupon AUTO_INCREMENT=5;
ALTER TABLE t_product AUTO_INCREMENT=5;
