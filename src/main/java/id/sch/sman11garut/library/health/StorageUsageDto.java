package id.sch.sman11garut.library.health;

import id.sch.sman11garut.library.common.StorageUsage;
import java.util.Map;

/**
 * Librarian storage report. Disk fields are null when the drive is not available; the
 * database figures are always filled so the librarian can still see what is stored.
 */
public record StorageUsageDto(
        boolean available,
        Long totalBytes,
        Long usedBytes,
        Long freeBytes,
        long minFreeBytes,
        StorageUsage books,
        Map<String, StorageUsage> archive,
        Long thumbnailsBytes,
        Long tempBytes) {
}
