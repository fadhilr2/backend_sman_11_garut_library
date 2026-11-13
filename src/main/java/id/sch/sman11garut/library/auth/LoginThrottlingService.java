package id.sch.sman11garut.library.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LoginThrottlingService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 5;

    private record AttemptRecord(int failedCount, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String username) {
        String key = username.toLowerCase().trim();
        AttemptRecord record = attempts.get(key);
        if (record == null) {
            return false;
        }

        if (record.lockedUntil() != null) {
            if (Instant.now().isBefore(record.lockedUntil())) {
                return true;
            } else {
                // Lock expired, clean up
                attempts.remove(key);
                return false;
            }
        }
        return false;
    }

    public void recordFailedAttempt(String username) {
        String key = username.toLowerCase().trim();
        attempts.compute(key, (k, existing) -> {
            int newCount = (existing == null) ? 1 : existing.failedCount() + 1;
            Instant lockedUntil = (newCount >= MAX_ATTEMPTS)
                    ? Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES)
                    : null;
            return new AttemptRecord(newCount, lockedUntil);
        });
    }

    public void resetAttempts(String username) {
        if (username != null) {
            attempts.remove(username.toLowerCase().trim());
        }
    }
}

