package id.sch.sman11garut.library.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class DiskSpaceGuardTest {

    @TempDir
    Path tempDir;

    private static StorageProperties properties(Path root, DataSize minFree) {
        return new StorageProperties(root, root.resolve("import"), minFree, false, java.time.Duration.ofHours(24));
    }

    private void mark() throws Exception {
        Files.createFile(tempDir.resolve(StorageDirectories.MARKER));
    }

    @Test
    void rejectsWhenFreeSpaceIsBelowTheThreshold() throws Exception {
        mark();
        DiskSpaceGuard guard = new DiskSpaceGuard(properties(tempDir, DataSize.ofBytes(Long.MAX_VALUE)));

        AppException ex = assertThrows(AppException.class, guard::ensureSpaceAvailable);
        assertEquals(503, ex.getStatus().value());
        assertEquals("STORAGE_FULL", ex.getCode());
    }

    @Test
    void reportsWhetherTheRootIsMountedAndWritable() throws Exception {
        mark();
        DiskSpaceGuard ready = new DiskSpaceGuard(properties(tempDir, DataSize.ofMegabytes(1)));
        assertTrue(ready.isStorageMountedAndWritable());
        assertFalse(Files.exists(tempDir.resolve(".write-probe")));

        StorageStatus status = ready.check();
        assertTrue(status.available());
        assertFalse(status.lowSpace());
        assertTrue(status.totalBytes() > 0);

        DiskSpaceGuard missing = new DiskSpaceGuard(properties(tempDir.resolve("missing"), DataSize.ofMegabytes(1)));
        assertFalse(missing.isStorageMountedAndWritable());
    }

    @Test
    void anEmptyMountPointWithoutTheMarkerIsNotMounted() {
        DiskSpaceGuard guard = new DiskSpaceGuard(properties(tempDir, DataSize.ofMegabytes(1)));

        assertFalse(guard.isMounted());
        assertFalse(guard.check().available());
        AppException ex = assertThrows(AppException.class, guard::ensureMounted);
        assertEquals("STORAGE_UNAVAILABLE", ex.getCode());
    }

    @Test
    void aMissingRootIsUnavailableNotFull() {
        DiskSpaceGuard guard = new DiskSpaceGuard(properties(tempDir.resolve("unplugged"), DataSize.ofMegabytes(1)));

        AppException ex = assertThrows(AppException.class, guard::ensureSpaceAvailable);
        assertEquals(503, ex.getStatus().value());
        assertEquals("STORAGE_UNAVAILABLE", ex.getCode());
    }

    @Test
    void aMountedRootWithSpacePassesTheUploadCheck() throws Exception {
        mark();
        DiskSpaceGuard guard = new DiskSpaceGuard(properties(tempDir, DataSize.ofBytes(1)));

        assertDoesNotThrow(guard::ensureSpaceAvailable);
    }
}
