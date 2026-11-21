package id.sch.sman11garut.library.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottlingServiceTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginThrottlingService service = new LoginThrottlingService(clock);

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailedAttempt(username);
        }
    }

    @Test
    void locksAfterFiveFailuresAndUnlocksAfterFiveMinutes() {
        fail("guru", LoginThrottlingService.MAX_ATTEMPTS);
        assertTrue(service.isBlocked("GURU "), "username matching ignores case and spaces");

        clock.advance(LoginThrottlingService.LOCK_DURATION);
        assertFalse(service.isBlocked("guru"));
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        fail("guru", LoginThrottlingService.MAX_ATTEMPTS - 1);
        clock.advance(LoginThrottlingService.FAILURE_WINDOW);

        // One more failure after the window starts a new count instead of locking.
        fail("guru", 1);
        assertFalse(service.isBlocked("guru"));
    }

    @Test
    void successfulLoginResetsTheCount() {
        fail("guru", LoginThrottlingService.MAX_ATTEMPTS - 1);
        service.resetAttempts("guru");
        fail("guru", 1);

        assertFalse(service.isBlocked("guru"));
    }

    @Test
    void staleEntriesAreRemovedSoTheMapStaysSmall() {
        for (int i = 0; i < 1001; i++) {
            fail("user" + i, 1);
        }
        clock.advance(LoginThrottlingService.FAILURE_WINDOW);
        fail("trigger-cleanup", 1);

        assertEquals(1, service.trackedUsernames());
    }
}
