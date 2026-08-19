package com.seckill.config;

import com.seckill.cache.HotKeyDetector;
import com.seckill.cache.HotKeyCacheManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus 自定义指标注册
 *
 * 面试可讲：
 * - Micrometer 是指标门面，Prometheus 是具体实现
 * - 自定义指标：热点Key数量、Caffeine命中率、秒杀请求计数
 * - Grafana 面板可视化展示，压测时实时观察
 */
@Configuration
@RequiredArgsConstructor
public class CacheMetricsConfig {

    @Bean
    public MeterBinder customMetrics(HotKeyDetector hotKeyDetector, HotKeyCacheManager cacheManager) {
        return registry -> {
            // 热点 Key 数量
            Gauge.builder("seckill.hotkey.count", hotKeyDetector, HotKeyDetector::getHotKeyCount)
                    .description("当前热点 Key 数量")
                    .register(registry);

            // Caffeine 命中率
            Gauge.builder("seckill.caffeine.hit.rate", cacheManager, mgr ->
                            mgr.getNormalCacheStats().hitRate())
                    .description("Caffeine 缓存命中率")
                    .register(registry);

            // Caffeine 淘汰数量
            Gauge.builder("seckill.caffeine.eviction.count", cacheManager, mgr ->
                            (double) mgr.getNormalCacheStats().evictionCount())
                    .description("Caffeine 缓存淘汰总次数")
                    .register(registry);
        };
    }
}