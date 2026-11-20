package id.sch.sman11garut.library.health;

import id.sch.sman11garut.library.storage.DiskSpaceGuard;
import id.sch.sman11garut.library.storage.StorageStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Shows up as the {@code storage} component of {@code /actuator/health}. */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    private final DiskSpaceGuard diskSpaceGuard;

    public StorageHealthIndicator(DiskSpaceGuard diskSpaceGuard) {
        this.diskSpaceGuard = diskSpaceGuard;
    }

    @Override
    public Health health() {
        StorageStatus status = diskSpaceGuard.check();
        Health.Builder builder;
        if (!status.available()) {
            builder = Health.down();
        } else if (status.lowSpace()) {
            builder = Health.outOfService();
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("mounted", status.mounted())
                .withDetail("writable", status.writable())
                .withDetail("freeBytes", status.usableBytes())
                .withDetail("minFreeBytes", status.minFreeBytes())
                .build();
    }
}
