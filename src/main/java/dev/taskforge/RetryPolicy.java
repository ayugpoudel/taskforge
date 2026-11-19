package dev.taskforge;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Durable scheduling uses this bounded delay; no in-memory retry timers. */
public final class RetryPolicy {
    public Duration delay(int attempt,RandomGenerator random) {
        long ceiling=Math.min(60_000L,1000L << Math.min(6,Math.max(0,attempt-1)));
        // Equal jitter: half the bounded exponential window plus randomness.
        return Duration.ofMillis(ceiling/2+random.nextLong(ceiling/2+1));
    }
}
