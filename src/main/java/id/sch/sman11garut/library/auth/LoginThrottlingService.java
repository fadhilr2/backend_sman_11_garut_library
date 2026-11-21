package id.sch.sman11garut.library.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * In-memory login throttling per username: 5 failures within 15 minutes lock the username
 * for 5 minutes. Old failures stop counting, and stale entries are removed so the map
 * can't grow without bound.
 */
@Service
public class LoginThrottlingService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    private static final int CLEANUP_THRESHOLD = 1000;

    /**
     * @param failedCount failures inside the current window
     * @param windowStart time of the first failure in the window
     * @param lockedUntil set once {@link #MAX_ATTEMPTS} is reached
     */
    private record AttemptRecord(int failedCount, Instant windowStart, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoginThrottlingService() {
        this(Clock.systemUTC());
    }

    LoginThrottlingService(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String username) {
        String key = key(username);
        AttemptRecord record = attempts.get(key);
        if (record == null) {
            return false;
        }
        Instant now = clock.instant();
        if (record.lockedUntil() != null && now.isBefore(record.lockedUntil())) {
            return true;
        }
        if (isStale(record, now)) {
            attempts.remove(key, record);
        }
        return false;
    }

    public void recordFailedAttempt(String username) {
        Instant now = clock.instant();
        attempts.compute(key(username), (k, existing) -> {
            // Start a new window if there is none, it expired, or a previous lock has ended.
            boolean newWindow = existing == null || isStale(existing, now);
            int count = newWindow ? 1 : existing.failedCount() + 1;
            Instant windowStart = newWindow ? now : existing.windowStart();
            Instant lockedUntil = count >= MAX_ATTEMPTS ? now.plus(LOCK_DURATION) : null;
            return new AttemptRecord(count, windowStart, lockedUntil);
        });
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.entrySet().removeIf(entry -> isStale(entry.getValue(), now));
        }
    }

    public void resetAttempts(String username) {
        if (username != null) {
            attempts.remove(key(username));
        }
    }

    int trackedUsernames() {
        return attempts.size();
    }

    /** A record no longer matters once its lock has ended, or (unlocked) its window has passed. */
    private static boolean isStale(AttemptRecord record, Instant now) {
        if (record.lockedUntil() != null) {
            return !now.isBefore(record.lockedUntil());
        }
        return !now.isBefore(record.windowStart().plus(FAILURE_WINDOW));
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase().trim();
    }
}
