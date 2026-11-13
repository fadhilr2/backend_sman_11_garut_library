package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DiskSpaceGuard {

    private final StorageProperties properties;

    public DiskSpaceGuard(StorageProperties properties) {
        this.properties = properties;
    }

    public void ensureSpaceAvailable() {
        Path root = properties.root();
        long usable;
        try {
            if (!Files.isDirectory(root)) {
                throw AppException.unavailable("STORAGE_FULL", "Insufficient storage space");
            }
            usable = Files.getFileStore(root).getUsableSpace();
        } catch (IOException ex) {
            throw AppException.unavailable("STORAGE_FULL", "Insufficient storage space");
        }
        if (usable < properties.minFreeSpace().toBytes()) {
            throw AppException.unavailable("STORAGE_FULL", "Insufficient storage space");
        }
    }

    public boolean isStorageMountedAndWritable() {
        Path root = properties.root();
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
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
