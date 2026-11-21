package id.sch.sman11garut.library.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.storage.DiskSpaceGuard;
import id.sch.sman11garut.library.storage.StorageDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class HealthServiceTest {

    @TempDir
    Path tempDir;

    private DiskSpaceGuard guard(boolean mounted, DataSize minFree) throws Exception {
        if (mounted) {
            Files.createFile(tempDir.resolve(StorageDirectories.MARKER));
        }
        return new DiskSpaceGuard(new StorageProperties(tempDir, tempDir.resolve("import"), minFree, false, java.time.Duration.ofHours(24)));
    }

    private static DataSource database(boolean up) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        if (up) {
            Connection connection = mock(Connection.class);
            when(connection.isValid(anyInt())).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(connection);
        } else {
            when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));
        }
        return dataSource;
    }

    @Test
    void upWhenDatabaseAndStorageAreFine() throws Exception {
        HealthDto health = new HealthService(database(true), guard(true, DataSize.ofBytes(1))).check();

        assertEquals(new HealthDto("UP", "UP", "UP"), health);
    }

    @Test
    void degradedWhenTheDriveIsUnplugged() throws Exception {
        HealthDto health = new HealthService(database(true), guard(false, DataSize.ofBytes(1))).check();

        assertEquals(new HealthDto("DEGRADED", "UP", "UNAVAILABLE"), health);
    }

    @Test
    void degradedWhenSpaceIsLow() throws Exception {
        HealthDto health = new HealthService(database(true), guard(true, DataSize.ofBytes(Long.MAX_VALUE))).check();

        assertEquals(new HealthDto("DEGRADED", "UP", "LOW_SPACE"), health);
    }

    @Test
    void downWhenTheDatabaseIsUnreachable() throws Exception {
        HealthDto health = new HealthService(database(false), guard(true, DataSize.ofBytes(1))).check();

        assertEquals(new HealthDto("DOWN", "DOWN", "UP"), health);
    }
}
