package id.sch.sman11garut.library.common;

/**
 * Item count and bytes from the database for one content group. Soft-deleted items are
 * reported separately because their files stay on disk until permanently removed.
 */
public record StorageUsage(long count, long bytes, long trashCount, long trashBytes) {

    public static final StorageUsage EMPTY = new StorageUsage(0, 0, 0, 0);

    public StorageUsage withActive(long count, long bytes) {
        return new StorageUsage(count, bytes, trashCount, trashBytes);
    }

    public StorageUsage withTrash(long count, long bytes) {
        return new StorageUsage(this.count, this.bytes, count, bytes);
    }
}
