package id.sch.sman11garut.library.trash;

import id.sch.sman11garut.library.archive.ArchiveService;
import id.sch.sman11garut.library.book.BookService;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.TrashProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly purge: permanently deletes books and archive items that have been in the trash
 * longer than {@code app.trash.retention}. Off when {@code app.trash.auto-purge} is false.
 * Books and archive are purged in separate transactions, so a failure in one leaves the other done.
 */
@Component
public class TrashPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeJob.class);

    private final BookService bookService;
    private final ArchiveService archiveService;
    private final TrashProperties properties;
    private final Clock clock;

    @Autowired
    public TrashPurgeJob(BookService bookService, ArchiveService archiveService, TrashProperties properties) {
        this(bookService, archiveService, properties, Clock.systemUTC());
    }

    TrashPurgeJob(BookService bookService, ArchiveService archiveService, TrashProperties properties, Clock clock) {
        this.bookService = bookService;
        this.archiveService = archiveService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.trash.purge-cron:0 30 2 * * *}")
    public void purgeScheduled() {
        if (properties.autoPurge()) {
            purgeExpired();
        }
    }

    /** @return number of items deleted (books + archive) */
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int purged = 0;
        try {
            purged += bookService.purgeDeletedBefore(cutoff);
        } catch (AppException ex) {
            // Typically STORAGE_UNAVAILABLE (drive unplugged): nothing was deleted, try again tomorrow.
            log.warn("Nightly trash purge skipped books: {}", ex.getMessage());
        }
        try {
            purged += archiveService.purgeDeletedBefore(cutoff);
        } catch (AppException ex) {
            log.warn("Nightly trash purge skipped archive items: {}", ex.getMessage());
        }
        if (purged > 0) {
            log.info("Nightly trash purge removed {} item(s) deleted before {}", purged, cutoff);
        }
        return purged;
    }
}
