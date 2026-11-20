package id.sch.sman11garut.library.health;

import id.sch.sman11garut.library.storage.DiskSpaceGuard;
import id.sch.sman11garut.library.storage.StorageStatus;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int DB_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final DiskSpaceGuard diskSpaceGuard;

    public HealthService(DataSource dataSource, DiskSpaceGuard diskSpaceGuard) {
        this.dataSource = dataSource;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    public HealthDto check() {
        boolean databaseUp = isDatabaseUp();
        String storage = storageState(diskSpaceGuard.check());

        String status;
        if (!databaseUp) {
            status = "DOWN";
        } else if (!"UP".equals(storage)) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }
        return new HealthDto(status, databaseUp ? "UP" : "DOWN", storage);
    }

    static String storageState(StorageStatus status) {
        if (!status.available()) {
            return "UNAVAILABLE";
        }
        return status.lowSpace() ? "LOW_SPACE" : "UP";
    }

    private boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_TIMEOUT_SECONDS);
        } catch (Exception ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return false;
        }
    }
}
