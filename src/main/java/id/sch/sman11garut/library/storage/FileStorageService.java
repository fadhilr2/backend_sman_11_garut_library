package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.storage.FileTypeDetector.DetectedFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final int BUFFER_SIZE = 8192;

    private final StorageProperties properties;
    private final StorageDirectories directories;
    private final DiskSpaceGuard diskSpaceGuard;
    private final FileTypeDetector fileTypeDetector;

    public FileStorageService(
            StorageProperties properties,
            StorageDirectories directories,
            DiskSpaceGuard diskSpaceGuard,
            FileTypeDetector fileTypeDetector) {
        this.properties = properties;
        this.directories = directories;
        this.diskSpaceGuard = diskSpaceGuard;
        this.fileTypeDetector = fileTypeDetector;
    }

    public StoredFileInfo storeFile(InputStream inputStream, String originalFilename, StorageCategory category) {
        try {
            directories.ensure();
        } catch (IOException ex) {
            throw storageFailure("Could not prepare storage directories", ex);
        }
        diskSpaceGuard.ensureSpaceAvailable();

        String safeName = fileNameOnly(originalFilename);
        Path temp = directories.tempDirectory().resolve(UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream hashed = new DigestInputStream(inputStream, digest)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                try (var output = Files.newOutputStream(temp)) {
                    int read;
                    while ((read = hashed.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
            long size = Files.size(temp);
            DetectedFile detected = fileTypeDetector.detect(temp, safeName, category);
            String relativePath = category.directory()
                    + "/"
                    + YEAR_MONTH.format(YearMonth.now())
                    + "/"
                    + UUID.randomUUID()
                    + "."
                    + detected.extension();
            Path target = properties.root().resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            String checksum = HexFormat.of().formatHex(digest.digest());
            return new StoredFileInfo(relativePath, safeName, detected.mimeType(), size, checksum);
        } catch (AppException ex) {
            throw ex;
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw storageFailure("Could not store the file", ex);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ex) {
                    log.warn("Could not delete temp file {}", temp, ex);
                }
            }
        }
    }

    public Path resolveSafePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw invalidPath();
        }
        String portable = relativePath.replace('\\', '/').trim();
        if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) {
            throw invalidPath();
        }
        Path root = properties.root().toAbsolutePath().normalize();
        Path resolved = root.resolve(portable).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw invalidPath();
        }
        for (Path part : Path.of(portable)) {
            if ("..".equals(part.toString())) {
                throw invalidPath();
            }
        }
        try {
            if (Files.exists(resolved)) {
                Path realRoot = root.toRealPath();
                Path real = resolved.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw invalidPath();
                }
                return real;
            }
        } catch (IOException ex) {
            throw invalidPath();
        }
        return resolved;
    }

    public void deleteStoredFile(String relativePath) {
        Path path = resolveSafePath(relativePath);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw storageFailure("Could not delete the stored file", ex);
        }
    }

    public Resource loadAsResource(String relativePath) {
        Path path = resolveSafePath(relativePath);
        if (!Files.isRegularFile(path)) {
            throw AppException.notFound("Stored file not found");
        }
        return new FileSystemResource(path);
    }

    private static String fileNameOnly(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? "file" : name;
    }

    private static AppException invalidPath() {
        return AppException.badRequest("INVALID_PATH", "Path traversal attempt detected");
    }

    private static AppException storageFailure(String message, Exception ex) {
        log.error(message, ex);
        return new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED", message);
    }
}
