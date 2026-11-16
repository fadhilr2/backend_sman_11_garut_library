package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.Visibility;
import java.time.Instant;
import java.time.LocalDate;

public record ArchiveSummaryDto(
        Long id,
        String title,
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
        Instant createdAt
) {
    public static ArchiveSummaryDto from(ArchiveItem item) {
        return new ArchiveSummaryDto(
                item.getId(),
                item.getTitle(),
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
                item.getCreatedAt()
        );
    }
}
