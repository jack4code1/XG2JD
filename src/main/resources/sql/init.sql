-- 秒杀系统初始化 SQL
CREATE DATABASE IF NOT EXISTS seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE seckill;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(128) NOT NULL,
    phone VARCHAR(20),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login_at DATETIME,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 优惠券表
CREATE TABLE IF NOT EXISTS t_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coupon_name VARCHAR(128) NOT NULL,
    coupon_desc VARCHAR(512),
    total_stock INT NOT NULL,
    remain_stock INT NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    per_user_max INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=未开始 1=进行中 2=已结束',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单表
CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAYING/PAID/USED/REFUNDING/REFUNDED/CANCELED/EXPIRED',
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_coupon_id (coupon_id),
    INDEX idx_order_no (order_no),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 事件日志表（本地消息表）
CREATE TABLE IF NOT EXISTS event_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL UNIQUE COMMENT '事件唯一ID',
    event_type VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATED/ORDER_PAID/ORDER_CANCELED等',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合根ID=orderNo',
    payload JSON NOT NULL COMMENT '事件载荷',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=消费确认 3=失败终态',
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 10,
    next_retry_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry_at),
    INDEX idx_aggregate (aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 对账快照表
CREATE TABLE IF NOT EXISTS reconciliation_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snap_date DATE NOT NULL,
    coupon_id BIGINT NOT NULL,
    redis_deduct INT NOT NULL DEFAULT 0 COMMENT 'Redis扣减次数',
    mysql_created INT NOT NULL DEFAULT 0 COMMENT 'MySQL订单创建数',
    mysql_paid INT NOT NULL DEFAULT 0 COMMENT 'MySQL已支付订单数',
    diff_detail JSON COMMENT '差异明细',
    reconciled_at DATETIME,
    UNIQUE KEY uk_date_coupon (snap_date, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;