package com.hypherionmc.sdlink.core.discord;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Limits code guesses across both DM and slash-command verification. */
public final class VerificationRateLimiter {

    public static final VerificationRateLimiter INSTANCE = new VerificationRateLimiter();
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(10).toMillis();
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong();

    private record Attempts(int count, long startedAt) {}

    private VerificationRateLimiter() {}

    public boolean allowAttempt(String discordId) {
        long now = System.currentTimeMillis();
        long previousCleanup = lastCleanup.get();
        if (now - previousCleanup >= WINDOW_MILLIS && lastCleanup.compareAndSet(previousCleanup, now)) {
            attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        }
        Attempts updated = attempts.compute(discordId, (key, prior) -> {
            if (prior == null || now - prior.startedAt >= WINDOW_MILLIS) return new Attempts(1, now);
            return new Attempts(Math.min(prior.count + 1, MAX_ATTEMPTS + 1), prior.startedAt);
        });
        return updated.count <= MAX_ATTEMPTS;
    }

    public void clear(String discordId) {
        attempts.remove(discordId);
    }
}
