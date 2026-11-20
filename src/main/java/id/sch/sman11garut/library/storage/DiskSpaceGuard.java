package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DiskSpaceGuard {

    private final StorageProperties properties;

    public DiskSpaceGuard(StorageProperties properties) {
        this.properties = properties;
    }

    /** Full check: marker, write probe and free space. Used by health and storage usage. */
    public StorageStatus check() {
        long minFree = properties.minFreeSpace().toBytes();
        if (!isMounted()) {
            return StorageStatus.unavailable(minFree);
        }
        Path root = properties.root();
        boolean writable = probeWritable(root);
        try {
            FileStore store = Files.getFileStore(root);
            return new StorageStatus(true, writable, store.getTotalSpace(), store.getUsableSpace(), minFree);
        } catch (IOException ex) {
            return StorageStatus.unavailable(minFree);
        }
    }

    /**
     * True when the root is a directory holding the marker file. An unmounted mount point
     * is an empty folder on the OS disk, so the marker is what proves the real drive is there.
     */
    public boolean isMounted() {
        Path root = properties.root();
        return root != null
                && Files.isDirectory(root)
                && Files.isRegularFile(root.resolve(StorageDirectories.MARKER));
    }

    public boolean isStorageMountedAndWritable() {
        return check().available();
    }

    /** For reads and deletes: the drive must be mounted. */
    public void ensureMounted() {
        if (!isMounted()) {
            throw unavailable();
        }
    }

    /** For uploads: the drive must be mounted, writable and above the free-space threshold. */
    public void ensureSpaceAvailable() {
        StorageStatus status = check();
        if (!status.available()) {
            throw unavailable();
        }
        if (status.lowSpace()) {
            throw AppException.unavailable("STORAGE_FULL", "Insufficient storage space");
        }
    }

    static AppException unavailable() {
        return AppException.unavailable("STORAGE_UNAVAILABLE", "File storage is not available right now");
    }

    private static boolean probeWritable(Path root) {
        Path probe = root.resolve(".write-probe");
        try {
            Files.writeString(probe, "ok");
            return true;
        } catch (IOException ex) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // The write already showed whether the root accepts files.
            }
        }
    }
}
