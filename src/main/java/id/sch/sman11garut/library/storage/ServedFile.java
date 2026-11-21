package id.sch.sman11garut.library.storage;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * A file a service has looked up and checked (existence, visibility), ready for a controller
 * to stream. Services return this so streaming happens outside their DB transaction.
 *
 * @param resource a file on disk, or an in-memory body (placeholders)
 * @param contentType the {@code Content-Type} to send
 * @param filename the original name, used for {@code Content-Disposition} on downloads
 */
public record ServedFile(Resource resource, String contentType, String filename) {

    public static ServedFile svg(String svg) {
        return new ServedFile(new ByteArrayResource(svg.getBytes(StandardCharsets.UTF_8)), "image/svg+xml", null);
    }

    /** {@code image/png} for {@code .png}, else {@code image/jpeg} (covers and thumbnails are JPEG or PNG). */
    public static String imageTypeOf(String storedPath) {
        return storedPath != null && storedPath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
    }
}
