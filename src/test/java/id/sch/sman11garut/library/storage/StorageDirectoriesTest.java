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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class StorageDirectoriesTest {

    @TempDir
    Path tempDir;

    private static StorageProperties properties(Path root, boolean autoCreateRoot) {
        return new StorageProperties(root, root.resolve("import"), DataSize.ofMegabytes(1), autoCreateRoot);
    }

    @Test
    void autoCreateMakesTheRootMarkerAndSubdirectories() throws Exception {
        Path root = tempDir.resolve("storage");
        new StorageDirectories(properties(root, true)).ensure();

        assertTrue(Files.isRegularFile(root.resolve(StorageDirectories.MARKER)));
        for (String subdirectory : StorageDirectories.SUBDIRECTORIES) {
            assertTrue(Files.isDirectory(root.resolve(subdirectory)));
        }
        // Running again keeps the existing marker.
        assertDoesNotThrow(() -> new StorageDirectories(properties(root, true)).ensure());
    }

    @Test
    void withoutAutoCreateAnUnmarkedRootIsUnavailableAndLeftUntouched() throws Exception {
        // An unmounted mount point: the folder exists but is empty.
        AppException ex = assertThrows(AppException.class,
                () -> new StorageDirectories(properties(tempDir, false)).ensure());
        assertEquals("STORAGE_UNAVAILABLE", ex.getCode());
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertEquals(0, entries.count());
        }

        Path missing = tempDir.resolve("missing");
        assertThrows(AppException.class, () -> new StorageDirectories(properties(missing, false)).ensure());
        assertFalse(Files.exists(missing));
    }

    @Test
    void withoutAutoCreateAMarkedRootGetsItsSubdirectories() throws Exception {
        Files.createFile(tempDir.resolve(StorageDirectories.MARKER));
        new StorageDirectories(properties(tempDir, false)).ensure();

        for (String subdirectory : StorageDirectories.SUBDIRECTORIES) {
            assertTrue(Files.isDirectory(tempDir.resolve(subdirectory)));
        }
    }

    @Test
    void startupDoesNotFailWhenTheDriveIsMissing() {
        StorageDirectories directories = new StorageDirectories(properties(tempDir.resolve("unplugged"), false));
        assertDoesNotThrow(() -> directories.run(null));
    }
}
