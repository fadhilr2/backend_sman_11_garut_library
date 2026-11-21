package id.sch.sman11garut.library.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.sch.sman11garut.library.config.StorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class TempFileSweeperTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @TempDir
    Path tempDir;

    private TempFileSweeper sweeper(boolean mounted) throws Exception {
        StorageProperties properties = new StorageProperties(
                tempDir, tempDir.resolve("import"), DataSize.ofMegabytes(1), false, Duration.ofHours(24));
        StorageDirectories directories = new StorageDirectories(properties);
        if (mounted) {
            Files.createFile(tempDir.resolve(StorageDirectories.MARKER));
            directories.ensure();
        } else {
            Files.createDirectories(tempDir.resolve("temp"));
        }
        return new TempFileSweeper(properties, directories, new DiskSpaceGuard(properties),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path tempFile(String name, Duration age) throws Exception {
        Path file = Files.writeString(tempDir.resolve("temp").resolve(name), "partial upload");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(age)));
        return file;
    }

    @Test
    void deletesOnlyFilesOlderThanTheMaxAge() throws Exception {
        TempFileSweeper sweeper = sweeper(true);
        Path stale = tempFile("stale.tmp", Duration.ofHours(25));
        Path fresh = tempFile("fresh.tmp", Duration.ofMinutes(5));

        assertEquals(1, sweeper.sweep());
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void doesNothingWhenTheDriveIsNotMounted() throws Exception {
        TempFileSweeper sweeper = sweeper(false);
        Path stale = tempFile("stale.tmp", Duration.ofDays(3));

        assertEquals(0, sweeper.sweep());
        assertTrue(Files.exists(stale));
    }
}
