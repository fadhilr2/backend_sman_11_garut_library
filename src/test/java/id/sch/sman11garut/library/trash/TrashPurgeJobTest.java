package id.sch.sman11garut.library.trash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.sch.sman11garut.library.archive.ArchiveService;
import id.sch.sman11garut.library.book.BookService;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.TrashProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TrashPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:30:00Z");
    private static final TrashProperties ON = new TrashProperties(true, Duration.ofDays(30), "0 30 2 * * *");

    private final BookService books = mock(BookService.class);
    private final ArchiveService archive = mock(ArchiveService.class);

    private TrashPurgeJob job(TrashProperties properties) {
        return new TrashPurgeJob(books, archive, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgesItemsDeletedBeforeTheRetentionCutoff() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(books.purgeDeletedBefore(cutoff)).thenReturn(2);
        when(archive.purgeDeletedBefore(cutoff)).thenReturn(3);

        assertEquals(5, job(ON).purgeExpired());
    }

    @Test
    void unpluggedDriveForBooksStillPurgesArchive() {
        when(books.purgeDeletedBefore(any())).thenThrow(
                AppException.unavailable("STORAGE_UNAVAILABLE", "File storage is not available right now"));
        when(archive.purgeDeletedBefore(any())).thenReturn(1);

        assertEquals(1, job(ON).purgeExpired());
    }

    @Test
    void scheduledRunDoesNothingWhenAutoPurgeIsOff() {
        job(new TrashProperties(false, Duration.ofDays(30), "0 30 2 * * *")).purgeScheduled();

        verify(books, never()).purgeDeletedBefore(any());
        verify(archive, never()).purgeDeletedBefore(any());
    }

    @Test
    void purgeDateIsDeletedAtPlusRetentionOrNullWhenOff() {
        Instant deletedAt = Instant.parse("2026-11-01T08:00:00Z");
        assertEquals(Instant.parse("2026-12-01T08:00:00Z"), ON.purgeAt(deletedAt));
        assertNull(new TrashProperties(false, Duration.ofDays(30), "x").purgeAt(deletedAt));
    }
}
