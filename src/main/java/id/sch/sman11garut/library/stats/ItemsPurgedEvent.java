package id.sch.sman11garut.library.stats;

import java.util.List;

/**
 * Published inside the purge transaction when items are permanently deleted from the trash.
 * Handled synchronously, so the item rows and their download logs go in the same commit.
 */
public record ItemsPurgedEvent(ItemType itemType, List<Long> itemIds) {
}
