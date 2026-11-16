package id.sch.sman11garut.library.storage;

import id.sch.sman11garut.library.common.AppException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

@Component
public class FileTypeDetector {

    static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "sh", "bat", "cmd", "jar", "js", "msi", "com");

    private static final Set<String> BOOK_MIMES = Set.of(
            "application/pdf",
            "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Set<String> COVER_MIMES = Set.of("image/jpeg", "image/png");

    private static final Set<String> ARCHIVE_MIMES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "video/mp4",
            "video/webm",
            "application/pdf",
            "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Map<String, Set<String>> EXTENSIONS_BY_MIME = Map.ofEntries(
            Map.entry("application/pdf", Set.of("pdf")),
            Map.entry("application/epub+zip", Set.of("epub")),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx")),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", Set.of("pptx")),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx")),
            Map.entry("image/jpeg", Set.of("jpg", "jpeg")),
            Map.entry("image/png", Set.of("png")),
            Map.entry("image/webp", Set.of("webp")),
            Map.entry("image/gif", Set.of("gif")),
            Map.entry("video/mp4", Set.of("mp4")),
            Map.entry("video/webm", Set.of("webm")));

    private static final Map<String, String> CANONICAL_EXTENSION = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/epub+zip", "epub"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/gif", "gif"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"));

    private final Tika tika = new Tika();

    public DetectedFile detect(Path file, String originalFilename, StorageCategory category) throws IOException {
        String extension = extensionOf(originalFilename);
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw AppException.badRequest("INVALID_FILE_TYPE", "This file type is not allowed");
        }

        String sniffed = sniff(file, extension);
        if (!allowedMimes(category).contains(sniffed)) {
            throw AppException.badRequest("INVALID_FILE_TYPE", "File type is not allowed");
        }
        Set<String> expected = EXTENSIONS_BY_MIME.get(sniffed);
        if (expected == null || !expected.contains(extension)) {
            throw AppException.badRequest("INVALID_FILE_TYPE", "File extension does not match its content");
        }
        return new DetectedFile(sniffed, CANONICAL_EXTENSION.get(sniffed));
    }

    private String sniff(Path file, String extension) throws IOException {
        String detected;
        try (InputStream input = TikaInputStream.get(file)) {
            detected = tika.detect(input);
        }
        detected = baseType(detected);
        if ("image/jpg".equals(detected)) {
            detected = "image/jpeg";
        }
        // Tika often reports a bare zip for EPUB containers when the mimetype entry is not read.
        if ("application/zip".equals(detected) && "epub".equals(extension)) {
            return "application/epub+zip";
        }
        return detected;
    }

    private static String baseType(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        int separator = mediaType.indexOf(';');
        String base = separator >= 0 ? mediaType.substring(0, separator) : mediaType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    static String extensionOf(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> allowedMimes(StorageCategory category) {
        return switch (category) {
            case BOOK -> BOOK_MIMES;
            case COVER, THUMBNAIL -> COVER_MIMES;
            case ARCHIVE -> ARCHIVE_MIMES;
        };
    }

    public record DetectedFile(String mimeType, String extension) {
    }
}
