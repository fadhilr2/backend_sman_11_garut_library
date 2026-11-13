package id.sch.sman11garut.library.storage;

public record StoredFileInfo(
        String relativePath,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String checksumSha256) {
}
