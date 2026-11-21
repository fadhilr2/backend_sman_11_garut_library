package id.sch.sman11garut.library.storage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class RangeFileResponder {

    private static final Logger log = LoggerFactory.getLogger(RangeFileResponder.class);

    private static final int BUFFER_SIZE = 8192;

    /**
     * Streams a looked-up file inline. Call this outside any transaction. A client that
     * disconnects mid-stream (closed tab, seek in a video) is normal and only logged at debug.
     */
    public void serve(HttpServletRequest request, HttpServletResponse response, ServedFile file) {
        try {
            write(request, response, file.resource(), file.contentType());
        } catch (IOException ex) {
            log.debug("Streaming stopped: {}", ex.getMessage());
        }
    }

    /**
     * Like {@link #serve} but as a download. The original name is encoded per RFC 6266 /
     * RFC 5987, so quotes, semicolons and non-ASCII characters can't break the header.
     */
    public void serveAttachment(HttpServletRequest request, HttpServletResponse response, ServedFile file) {
        String name = file.filename() == null || file.filename().isBlank() ? "file" : file.filename();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        serve(request, response, file);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, Resource resource, String contentType)
            throws IOException {
        if (!resource.isFile()) {
            // In-memory body (e.g. an SVG placeholder): no ranges, no file metadata.
            byte[] body = resource.getContentAsByteArray();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        if (!resource.exists() || !resource.isReadable()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long total = resource.contentLength();
        long lastModified = resource.lastModified();
        String etag = "\"" + total + "-" + lastModified + "\"";
        String type = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

        String header = request.getHeader(HttpHeaders.RANGE);
        if (header == null || header.isBlank()) {
            writeFull(response, resource, type, total, etag, lastModified);
            return;
        }
        ByteRange range = parseRange(header, total);
        if (range == null) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
            return;
        }
        writePartial(response, resource, type, total, etag, lastModified, range);
    }

    private void writeFull(
            HttpServletResponse response,
            Resource resource,
            String contentType,
            long total,
            String etag,
            long lastModified) throws IOException {
        applyCommonHeaders(response, contentType, etag, lastModified);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLengthLong(total);
        copy(resource, 0, total, response.getOutputStream());
    }

    private void writePartial(
            HttpServletResponse response,
            Resource resource,
            String contentType,
            long total,
            String etag,
            long lastModified,
            ByteRange range) throws IOException {
        long length = range.end() - range.start() + 1;
        applyCommonHeaders(response, contentType, etag, lastModified);
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + total);
        response.setContentLengthLong(length);
        copy(resource, range.start(), length, response.getOutputStream());
    }

    private static void applyCommonHeaders(
            HttpServletResponse response, String contentType, String etag, long lastModified) {
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModified);
        response.setContentType(contentType);
    }

    private static ByteRange parseRange(String header, long total) {
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, "bytes=".length()) || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        if (startText.isEmpty() || total <= 0) {
            return null;
        }
        long start;
        long end;
        try {
            start = Long.parseLong(startText);
            end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (start < 0 || end < start || start >= total) {
            return null;
        }
        // RFC 9110: an end past the last byte means "to the end of the file", not an error.
        return new ByteRange(start, Math.min(end, total - 1));
    }

    private static void copy(Resource resource, long start, long length, OutputStream output) throws IOException {
        try (FileChannel channel = FileChannel.open(resource.getFile().toPath(), StandardOpenOption.READ)) {
            long position = start;
            long remaining = length;
            byte[] buffer = new byte[BUFFER_SIZE];
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, toRead);
                int read = channel.read(byteBuffer, position);
                if (read <= 0) {
                    break;
                }
                output.write(buffer, 0, read);
                position += read;
                remaining -= read;
            }
            output.flush();
        }
    }

    private record ByteRange(long start, long end) {
    }
}
