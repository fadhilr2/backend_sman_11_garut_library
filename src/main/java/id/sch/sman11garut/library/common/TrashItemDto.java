package id.sch.sman11garut.library.common;

import java.time.Instant;

/**
 * One row of a trash list (books or archive).
 *
 * @param kind the book type ({@code EBOOK}, ...) or archive media type ({@code IMAGE}, ...)
 * @param uploadedByName null for bulk-imported items
 * @param purgeAt when the nightly job deletes it for good; null when automatic purging is off
 */
public record TrashItemDto(
        Long id,
        String title,
        String kind,
        String originalName,
        String mimeType,
        long fileSize,
        String uploadedByName,
        Instant deletedAt,
        Instant purgeAt) {
}
