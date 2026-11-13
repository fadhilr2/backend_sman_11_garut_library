package id.sch.sman11garut.library.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Path root,
        Path importDir,
        @DefaultValue("5GB") DataSize minFreeSpace) {
}