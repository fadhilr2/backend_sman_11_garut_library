package id.sch.sman11garut.library.media;

import id.sch.sman11garut.library.archive.MediaType;

public record ArchiveItemCreatedEvent(
        Long itemId,
        String storedPath,
        MediaType mediaType,
        String mimeType
) {}
