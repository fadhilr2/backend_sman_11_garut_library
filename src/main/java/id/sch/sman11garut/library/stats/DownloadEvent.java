package id.sch.sman11garut.library.stats;

import java.time.Instant;

public record DownloadEvent(
        ItemType itemType,
        Long itemId,
        Instant downloadedAt
) {}
