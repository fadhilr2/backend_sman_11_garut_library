package id.sch.sman11garut.library.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class FileStorageServiceTest {

    private static final byte[] PDF = """
            %PDF-1.4
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj
            trailer<</Root 1 0 R>>
            %%EOF
            """.getBytes(StandardCharsets.US_ASCII);

    private static final byte[] EXECUTABLE = new byte[] {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

    @TempDir
    Path tempDir;

    private FileStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        StorageProperties properties = new StorageProperties(
                tempDir, tempDir.resolve("import"), DataSize.ofMegabytes(1));
        StorageDirectories directories = new StorageDirectories(properties);
        directories.ensure();
        service = new FileStorageService(
                properties, directories, new DiskSpaceGuard(properties), new FileTypeDetector());
    }

    @Test
    void storesAFileAndKeepsAStableChecksum() throws Exception {
        String expectedChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));

        StoredFileInfo first = service.storeFile(new ByteArrayInputStream(PDF), "modul.pdf", StorageCategory.BOOK);
        StoredFileInfo second = service.storeFile(new ByteArrayInputStream(PDF), "modul.pdf", StorageCategory.BOOK);

        assertEquals(expectedChecksum, first.checksumSha256());
        assertEquals(first.checksumSha256(), second.checksumSha256());
        assertNotEquals(first.relativePath(), second.relativePath());
        assertTrue(first.relativePath().matches("books/\\d{4}/\\d{2}/[0-9a-fA-F-]{36}\\.pdf"));
        assertEquals("application/pdf", first.mimeType());
        assertEquals(PDF.length, first.sizeBytes());
        assertArrayEquals(PDF, Files.readAllBytes(tempDir.resolve(first.relativePath())));
        assertTempDirectoryEmpty();
    }

    @Test
    void rejectsBlockedExtensionsAndContentThatDoesNotMatch() throws Exception {
        AppException renamed = assertThrows(AppException.class,
                () -> service.storeFile(new ByteArrayInputStream(PDF), "modul.exe", StorageCategory.BOOK));
        assertEquals("INVALID_FILE_TYPE", renamed.getCode());

        AppException disguised = assertThrows(AppException.class,
                () -> service.storeFile(new ByteArrayInputStream(EXECUTABLE), "notes.pdf", StorageCategory.BOOK));
        assertEquals("INVALID_FILE_TYPE", disguised.getCode());
        assertTempDirectoryEmpty();
    }

    @Test
    void rejectsPathTraversal() {
        AppException relative = assertThrows(AppException.class, () -> service.resolveSafePath("../secret"));
        assertEquals("INVALID_PATH", relative.getCode());

        String absolute = tempDir.resolve("..").resolve("outside.bin").toAbsolutePath().toString();
        AppException absolutePath = assertThrows(AppException.class, () -> service.resolveSafePath(absolute));
        assertEquals("INVALID_PATH", absolutePath.getCode());
    }

    @Test
    void deletesAStoredFileAndLoadsItAsAResource() throws Exception {
        StoredFileInfo stored = service.storeFile(new ByteArrayInputStream(PDF), "modul.pdf", StorageCategory.BOOK);
        assertArrayEquals(PDF, service.loadAsResource(stored.relativePath()).getContentAsByteArray());

        service.deleteStoredFile(stored.relativePath());
        assertFalse(Files.exists(tempDir.resolve(stored.relativePath())));

        AppException missing = assertThrows(AppException.class, () -> service.loadAsResource(stored.relativePath()));
        assertEquals("NOT_FOUND", missing.getCode());
    }

    private void assertTempDirectoryEmpty() throws Exception {
        try (Stream<Path> entries = Files.list(tempDir.resolve("temp"))) {
            assertEquals(0, entries.count());
        }
    }
}
