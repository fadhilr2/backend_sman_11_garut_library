package id.sch.sman11garut.library.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RangeFileResponderTest {

    private static final int FILE_SIZE = 2048;

    @TempDir
    Path tempDir;

    private Path file;
    private byte[] data;
    private final RangeFileResponder responder = new RangeFileResponder();

    @BeforeEach
    void setUp() throws Exception {
        data = new byte[FILE_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        file = tempDir.resolve("sample.bin");
        Files.write(file, data);
    }

    @Test
    void returnsPartialContentForASingleRange() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.RANGE, "bytes=0-1023");
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.write(request, response, new FileSystemResource(file), "application/octet-stream");

        assertEquals(206, response.getStatus());
        assertEquals("bytes 0-1023/2048", response.getHeader(HttpHeaders.CONTENT_RANGE));
        assertEquals("bytes", response.getHeader(HttpHeaders.ACCEPT_RANGES));
        assertNotNull(response.getHeader(HttpHeaders.ETAG));
        assertNotNull(response.getHeader(HttpHeaders.LAST_MODIFIED));
        assertArrayEquals(Arrays.copyOfRange(data, 0, 1024), response.getContentAsByteArray());
    }

    @Test
    void returnsTheWholeFileWhenNoRangeIsRequested() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.write(new MockHttpServletRequest(), response, new FileSystemResource(file), "application/pdf");

        assertEquals(200, response.getStatus());
        assertEquals("bytes", response.getHeader(HttpHeaders.ACCEPT_RANGES));
        assertEquals("application/pdf", response.getContentType());
        assertArrayEquals(data, response.getContentAsByteArray());
    }

    @Test
    void rejectsARangePastTheEnd() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.RANGE, "bytes=3000-4000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.write(request, response, new FileSystemResource(file), "application/octet-stream");

        assertEquals(416, response.getStatus());
        assertEquals("bytes */2048", response.getHeader(HttpHeaders.CONTENT_RANGE));
        assertEquals(0, response.getContentAsByteArray().length);
    }
}
