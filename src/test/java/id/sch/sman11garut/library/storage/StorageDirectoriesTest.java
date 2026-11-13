package id.sch.sman11garut.library.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import id.sch.sman11garut.library.config.StorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class StorageDirectoriesTest {

    @TempDir
    Path tempDir;

    @Test
    void createsStorageSubdirectories() throws Exception {
        StorageProperties properties = new StorageProperties(
                tempDir, tempDir.resolve("import"), DataSize.ofMegabytes(1));
        new StorageDirectories(properties).ensure();

        for (String subdirectory : StorageDirectories.SUBDIRECTORIES) {
            assertTrue(Files.isDirectory(tempDir.resolve(subdirectory)));
        }
    }
}
