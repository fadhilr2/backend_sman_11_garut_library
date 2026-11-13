package id.sch.sman11garut.library.storage;

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

    @Test
    void rejectsWhenFreeSpaceIsBelowTheThreshold() {
        StorageProperties properties = new StorageProperties(
                tempDir, tempDir.resolve("import"), DataSize.ofBytes(Long.MAX_VALUE));
        DiskSpaceGuard guard = new DiskSpaceGuard(properties);

        AppException ex = assertThrows(AppException.class, guard::ensureSpaceAvailable);
        assertEquals(503, ex.getStatus().value());
        assertEquals("STORAGE_FULL", ex.getCode());
    }

    @Test
    void reportsWhetherTheRootIsMountedAndWritable() throws Exception {
        StorageProperties ready = new StorageProperties(
                tempDir, tempDir.resolve("import"), DataSize.ofMegabytes(1));
        assertTrue(new DiskSpaceGuard(ready).isStorageMountedAndWritable());
        assertFalse(Files.exists(tempDir.resolve(".write-probe")));

        StorageProperties missing = new StorageProperties(
                tempDir.resolve("missing"), tempDir.resolve("import"), DataSize.ofMegabytes(1));
        assertFalse(new DiskSpaceGuard(missing).isStorageMountedAndWritable());
    }
}
