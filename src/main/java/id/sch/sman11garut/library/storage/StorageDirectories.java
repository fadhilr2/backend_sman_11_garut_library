package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StorageDirectories implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageDirectories.class);

    /** Marker file proving the storage root is the real library drive. */
    public static final String MARKER = ".library-storage";

    static final List<String> SUBDIRECTORIES = List.of("temp", "books", "archive", "thumbnails");

    private final StorageProperties properties;

    public StorageDirectories(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Prepares the storage sub-folders. With {@code auto-create-root} the root and marker are
     * created when missing; without it, a missing root or marker means the drive is not mounted
     * and nothing is created ({@code 503 STORAGE_UNAVAILABLE}).
     */
    public void ensure() throws IOException {
        Path root = properties.root();
        if (properties.autoCreateRoot()) {
            Files.createDirectories(root);
            try {
                Files.createFile(root.resolve(MARKER));
            } catch (FileAlreadyExistsException ignored) {
                // Already marked.
            }
        } else if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve(MARKER))) {
            throw DiskSpaceGuard.unavailable();
        }
        for (String subdirectory : SUBDIRECTORIES) {
            Files.createDirectories(root.resolve(subdirectory));
        }
    }

    public Path tempDirectory() {
        return properties.root().resolve("temp");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            ensure();
        } catch (AppException | IOException ex) {
            // Degraded mode: browsing must keep working while the drive is unplugged.
            log.warn("File storage is not available at {} ({}). Starting in degraded mode: "
                    + "file requests return 503 until the drive is mounted with its {} marker.",
                    properties.root(), ex.getMessage(), MARKER);
        }
    }
}
