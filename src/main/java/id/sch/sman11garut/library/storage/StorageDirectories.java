package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StorageDirectories implements ApplicationRunner {

    static final List<String> SUBDIRECTORIES = List.of("temp", "books", "archive", "thumbnails");

    private final StorageProperties properties;

    public StorageDirectories(StorageProperties properties) {
        this.properties = properties;
    }

    public void ensure() throws IOException {
        Files.createDirectories(properties.root());
        for (String subdirectory : SUBDIRECTORIES) {
            Files.createDirectories(properties.root().resolve(subdirectory));
        }
    }

    public Path tempDirectory() {
        return properties.root().resolve("temp");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensure();
    }
}
