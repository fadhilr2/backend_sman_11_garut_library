package id.sch.sman11garut.library.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Storage settings ({@code app.storage.*}).
 *
 * @param autoCreateRoot when true (dev), the app creates the storage root and its
 *     {@code .library-storage} marker itself. When false (prod), the root must already be
 *     a mounted drive containing the marker; the app never creates it, so an unmounted
 *     mount point is never filled by mistake.
 * @param tempMaxAge files in {@code <root>/temp} older than this are deleted by the hourly
 *     sweep (leftovers of uploads interrupted by a crash or power loss)
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Path root,
        Path importDir,
        @DefaultValue("5GB") DataSize minFreeSpace,
        @DefaultValue("false") boolean autoCreateRoot,
        @DefaultValue("24h") Duration tempMaxAge) {
}
