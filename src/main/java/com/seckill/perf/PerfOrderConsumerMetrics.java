package com.seckill.perf;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory, perf-profile-only timing window for the real order consumer. */
@Profile("perf")
@Component
public class PerfOrderConsumerMetrics {
    private volatile Window window = new Window();

    public void reset() {
        window = new Window();
    }

    public void record(Outcome outcome, long durationNanos) {
        Window current = window;
        current.count.incrementAndGet();
        current.outcome(outcome).incrementAndGet();
        current.totalNanos.addAndGet(durationNanos);
        current.durationsNanos.add(durationNanos);
    }

    public Snapshot snapshot() {
        Window current = window;
        List<Long> durations = new ArrayList<>(current.durationsNanos);
        durations.sort(Long::compareTo);
        long count = current.count.get();
        return new Snapshot(count, current.created.get(), current.duplicates.get(), current.failed.get(),
                count == 0 ? 0 : roundMillis((double) current.totalNanos.get() / count),
                percentileMillis(durations, 0.50), percentileMillis(durations, 0.95), percentileMillis(durations, 0.99),
                count == 0 ? 0 : roundMillis(durations.get(durations.size() - 1)));
    }

    private long percentileMillis(List<Long> values, double ratio) {
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(values.size() * ratio) - 1);
        return roundMillis(values.get(index));
    }

    private long roundMillis(double nanos) {
        return Math.round(nanos / 1_000_000d);
    }

    private static final class Window {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong created = new AtomicLong();
        private final AtomicLong duplicates = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final ConcurrentLinkedQueue<Long> durationsNanos = new ConcurrentLinkedQueue<>();

        private AtomicLong outcome(Outcome outcome) {
            return switch (outcome) {
                case CREATED -> created;
                case DUPLICATE -> duplicates;
                case FAILED -> failed;
            };
        }
    }

    public enum Outcome { CREATED, DUPLICATE, FAILED }

    public record Snapshot(long count, long created, long duplicates, long failed,
                           long averageMs, long p50Ms, long p95Ms, long p99Ms, long maxMs) {}
}
