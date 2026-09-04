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
    role VARCHAR(16),
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商户表必须在测试种子数据写入前创建。
CREATE TABLE IF NOT EXISTS t_merchant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    shop_name VARCHAR(128) NOT NULL,
    shop_desc VARCHAR(512),
    category VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户对店铺的公开点评。
CREATE TABLE IF NOT EXISTS t_shop_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_review_merchant_time (merchant_id, created_at),
    INDEX idx_shop_review_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 优惠券表
CREATE TABLE IF NOT EXISTS t_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coupon_name VARCHAR(128) NOT NULL,
    coupon_desc VARCHAR(512),
    discount_amount DECIMAL(10,2) DEFAULT 0,
    merchant_id BIGINT,
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

-- 商品表：商品需要支付，优惠券只在商品结算时抵扣
CREATE TABLE IF NOT EXISTS t_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    price DECIMAL(10,2) NOT NULL,
    remain_stock INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_product_merchant (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单表
CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL,
    coupon_id BIGINT,
    product_id BIGINT,
    order_type VARCHAR(24) NOT NULL DEFAULT 'COUPON_CLAIM',
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAYING/PAID/USED/REFUNDING/REFUNDED/CANCELED/EXPIRED',
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    original_amount DECIMAL(10,2),
    discount_amount DECIMAL(10,2) DEFAULT 0,
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

-- AI 运营调用审计：记录商户问题、意图、耗时和是否降级，不保存完整模型原文
CREATE TABLE IF NOT EXISTS ai_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    query VARCHAR(512) NOT NULL,
    intent VARCHAR(32),
    elapsed_ms BIGINT,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_audit_merchant_time (merchant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 可执行任务：保存已确认前不可变的 Proposal，避免执行阶段重新生成参数
CREATE TABLE IF NOT EXISTS ai_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL UNIQUE,
    merchant_id BIGINT NOT NULL,
    query VARCHAR(512) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_coupon_id BIGINT,
    proposal_json TEXT NOT NULL,
    result_json TEXT,
    requires_confirmation BOOLEAN NOT NULL DEFAULT TRUE,
    confirmed_at DATETIME,
    executing_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_task_merchant_time (merchant_id, created_at),
    INDEX idx_ai_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 工具调用审计：记录每个动作的输入、状态、结果和错误
CREATE TABLE IF NOT EXISTS ai_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json TEXT NOT NULL,
    result_json TEXT,
    error_message VARCHAR(512),
    executed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_action_task_time (task_id, created_at),
    INDEX idx_ai_action_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 优惠活动配置的不可变发布历史，可用于商家查看和回滚。
CREATE TABLE IF NOT EXISTS coupon_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    action VARCHAR(32) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_coupon_version_coupon (coupon_id, version_no),
    INDEX idx_coupon_version_merchant (merchant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商家活动状态、AI 执行结果等站内通知。
CREATE TABLE IF NOT EXISTS user_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    read_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_recipient (recipient_id, read_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
