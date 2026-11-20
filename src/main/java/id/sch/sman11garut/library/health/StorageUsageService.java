package id.sch.sman11garut.library.health;

import id.sch.sman11garut.library.archive.ArchiveService;
import id.sch.sman11garut.library.book.BookService;
import id.sch.sman11garut.library.common.StorageUsage;
import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.storage.DiskSpaceGuard;
import id.sch.sman11garut.library.storage.StorageStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StorageUsageService {

    private static final Logger log = LoggerFactory.getLogger(StorageUsageService.class);

    private final DiskSpaceGuard diskSpaceGuard;
    private final StorageProperties properties;
    private final BookService bookService;
    private final ArchiveService archiveService;

    public StorageUsageService(
            DiskSpaceGuard diskSpaceGuard,
            StorageProperties properties,
            BookService bookService,
            ArchiveService archiveService) {
        this.diskSpaceGuard = diskSpaceGuard;
        this.properties = properties;
        this.bookService = bookService;
        this.archiveService = archiveService;
    }

    public StorageUsageDto getUsage() {
        StorageStatus status = diskSpaceGuard.check();

        StorageUsage books = bookService.storageUsage();
        Map<String, StorageUsage> archive = new LinkedHashMap<>();
        archiveService.storageUsageByMediaType().forEach((type, usage) -> archive.put(type.name(), usage));

        if (!status.mounted()) {
            return new StorageUsageDto(false, null, null, null, status.minFreeBytes(),
                    books, archive, null, null);
        }

        // Thumbnails and temp files have no size column in the DB, so measure them on disk.
        return new StorageUsageDto(
                status.available(),
                status.totalBytes(),
                status.totalBytes() - status.usableBytes(),
                status.usableBytes(),
                status.minFreeBytes(),
                books,
                archive,
                directorySize(properties.root().resolve("thumbnails")),
                directorySize(properties.root().resolve("temp")));
    }

    static Long directorySize(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(StorageUsageService::sizeOf).sum();
        } catch (IOException | UncheckedIOException ex) {
            log.warn("Could not measure {}: {}", directory, ex.getMessage());
            return null;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L; // File removed while walking (e.g. a finished temp upload).
        }
    }
}
