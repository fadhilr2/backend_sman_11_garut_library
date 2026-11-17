package id.sch.sman11garut.library.stats;

import id.sch.sman11garut.library.archive.ArchiveRepository;
import id.sch.sman11garut.library.book.BookRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DownloadEventListener {

    private static final Logger log = LoggerFactory.getLogger(DownloadEventListener.class);

    private final DownloadLogRepository downloadLogRepository;
    private final BookRepository bookRepository;
    private final ArchiveRepository archiveRepository;

    public DownloadEventListener(
            DownloadLogRepository downloadLogRepository,
            BookRepository bookRepository,
            ArchiveRepository archiveRepository) {
        this.downloadLogRepository = downloadLogRepository;
        this.bookRepository = bookRepository;
        this.archiveRepository = archiveRepository;
    }

    @Async("statsExecutor")
    @EventListener
    @Transactional
    public void onDownload(DownloadEvent event) {
        if (event == null || event.itemId() == null || event.itemType() == null) {
            log.warn("Received invalid download event: {}", event);
            return;
        }

        try {
            DownloadLog logEntry = new DownloadLog(
                    event.itemType(),
                    event.itemId(),
                    event.downloadedAt() != null ? event.downloadedAt() : Instant.now()
            );
            downloadLogRepository.save(logEntry);

            if (event.itemType() == ItemType.BOOK) {
                bookRepository.incrementDownloadCount(event.itemId());
            } else if (event.itemType() == ItemType.ARCHIVE) {
                archiveRepository.incrementViewCount(event.itemId());
            }
            log.debug("Processed download event for {} id {}", event.itemType(), event.itemId());
        } catch (Exception ex) {
            log.error("Failed to process download event for {} id {}: {}",
                    event.itemType(), event.itemId(), ex.getMessage(), ex);
        }
    }
}
