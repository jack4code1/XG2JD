package com.seckill.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存值包装类 — 支持逻辑过期
 *
 * 面试可讲：
 * - 逻辑过期(逻辑失效但物理不过期) vs 物理过期(到了就删)
 * - 读取时发现逻辑过期 → 返回旧值 + 异步刷新，不阻塞用户请求
 * - 避免缓存击穿：互斥锁方案会阻塞，逻辑过期方案永远有旧值兜底
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheValue<T> {

    private T data;

    /** 逻辑过期时间戳（毫秒），过期后触发异步刷新但不阻塞读 */
    private long logicExpireTime;

    /** 物理过期兜底时间戳（毫秒），过期后强制阻塞重建 */
    private long physicalExpireTime;

    @JsonIgnore
    public boolean isLogicallyExpired() {
        return System.currentTimeMillis() > logicExpireTime;
    }

    @JsonIgnore
    public boolean isPhysicallyExpired() {
        return System.currentTimeMillis() > physicalExpireTime;
    }
}
