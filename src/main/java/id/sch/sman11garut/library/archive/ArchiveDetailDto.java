package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.Visibility;
import java.time.Instant;
import java.time.LocalDate;

public record ArchiveDetailDto(
        Long id,
        String title,
        String description,
        MediaType mediaType,
        LocalDate eventDate,
        Visibility visibility,
        String thumbnailPath,
        Integer width,
        Integer height,
        Integer durationSeconds,
        int viewCount,
        Integer categoryId,
        String categoryName,
        String originalName,
        String mimeType,
        long fileSize,
        String checksumSha256,
        Long uploadedById,
        String uploadedByName,
        Instant createdAt,
        Instant updatedAt
) {
    public static ArchiveDetailDto from(ArchiveItem item) {
        return new ArchiveDetailDto(
                item.getId(),
                item.getTitle(),
                item.getDescription(),
                item.getMediaType(),
                item.getEventDate(),
                item.getVisibility(),
                item.getThumbnailPath(),
                item.getWidth(),
                item.getHeight(),
                item.getDurationSeconds(),
                item.getViewCount(),
                item.getCategory() != null ? item.getCategory().getId() : null,
                item.getCategory() != null ? item.getCategory().getName() : null,
                item.getOriginalName(),
                item.getMimeType(),
                item.getFileSize(),
                item.getChecksumSha256(),
                item.getUploadedBy() != null ? item.getUploadedBy().getId() : null,
                item.getUploadedBy() != null ? item.getUploadedBy().getFullName() : null,
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
