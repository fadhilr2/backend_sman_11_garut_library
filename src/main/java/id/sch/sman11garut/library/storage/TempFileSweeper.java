package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes leftovers in {@code <root>/temp}. A normal upload always moves or deletes its temp
 * file, so anything older than {@code app.storage.temp-max-age} was left by a crash or power
 * loss mid-upload.
 */
@Component
public class TempFileSweeper {

    private static final Logger log = LoggerFactory.getLogger(TempFileSweeper.class);

    private final StorageProperties properties;
    private final StorageDirectories directories;
    private final DiskSpaceGuard diskSpaceGuard;
    private final Clock clock;

    @Autowired
    public TempFileSweeper(StorageProperties properties, StorageDirectories directories, DiskSpaceGuard diskSpaceGuard) {
        this(properties, directories, diskSpaceGuard, Clock.systemUTC());
    }

    TempFileSweeper(StorageProperties properties, StorageDirectories directories, DiskSpaceGuard diskSpaceGuard,
            Clock clock) {
        this.properties = properties;
        this.directories = directories;
        this.diskSpaceGuard = diskSpaceGuard;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void sweepScheduled() {
        sweep();
    }

    /** @return number of files deleted */
    public int sweep() {
        if (!diskSpaceGuard.isMounted()) {
            return 0; // Drive unplugged: nothing to clean, and never touch the bare mount point.
        }
        Path temp = directories.tempDirectory();
        if (!Files.isDirectory(temp)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(properties.tempMaxAge());
        List<Path> candidates;
        try (Stream<Path> files = Files.list(temp)) {
            candidates = files.filter(Files::isRegularFile).toList();
        } catch (IOException ex) {
            log.warn("Could not list temp folder {}: {}", temp, ex.getMessage());
            return 0;
        }

        int deleted = 0;
        long bytes = 0;
        for (Path file : candidates) {
            try {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    long size = Files.size(file);
                    if (Files.deleteIfExists(file)) {
                        deleted++;
                        bytes += size;
                    }
                }
            } catch (IOException ex) {
                // Removed meanwhile, or locked by an upload in progress: try again next hour.
                log.debug("Skipped temp file {}: {}", file, ex.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("Temp sweep removed {} stale file(s), {} bytes", deleted, bytes);
        }
        return deleted;
    }
}
