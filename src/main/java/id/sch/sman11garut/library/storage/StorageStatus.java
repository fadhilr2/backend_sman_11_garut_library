package id.sch.sman11garut.library.storage;

/**
 * Snapshot of the storage drive.
 *
 * @param mounted the root is a directory and contains the {@code .library-storage} marker
 * @param writable a probe file could be written to the root
 * @param totalBytes size of the drive (0 when not mounted)
 * @param usableBytes free space usable by the app (0 when not mounted)
 * @param minFreeBytes configured threshold below which uploads are refused
 */
public record StorageStatus(
        boolean mounted,
        boolean writable,
        long totalBytes,
        long usableBytes,
        long minFreeBytes) {

    public static StorageStatus unavailable(long minFreeBytes) {
        return new StorageStatus(false, false, 0, 0, minFreeBytes);
    }

    /** Files can be read and written. */
    public boolean available() {
        return mounted && writable;
    }

    /** Free space is under the threshold (only meaningful when available). */
    public boolean lowSpace() {
        return usableBytes < minFreeBytes;
    }
}
