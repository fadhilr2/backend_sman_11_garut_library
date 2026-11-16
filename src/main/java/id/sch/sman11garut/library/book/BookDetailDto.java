package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.common.Visibility;
import java.time.Instant;

public record BookDetailDto(
        Long id,
        String title,
        String author,
        String publisher,
        Integer publicationYear,
        String description,
        BookType bookType,
        Integer categoryId,
        String categoryName,
        GradeLevel gradeLevel,
        String curriculum,
        Visibility visibility,
        String originalName,
        String mimeType,
        long fileSize,
        String checksumSha256,
        boolean hasCover,
        int downloadCount,
        Long uploadedById,
        String uploadedByName,
        Instant createdAt,
        Instant updatedAt) {

    public static BookDetailDto from(Book book) {
        return new BookDetailDto(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPublisher(),
                book.getPublicationYear() != null ? book.getPublicationYear().intValue() : null,
                book.getDescription(),
                book.getBookType(),
                book.getCategory() != null ? book.getCategory().getId() : null,
                book.getCategory() != null ? book.getCategory().getName() : null,
                book.getGradeLevel(),
                book.getCurriculum(),
                book.getVisibility(),
                book.getOriginalName(),
                book.getMimeType(),
                book.getFileSize(),
                book.getChecksumSha256(),
                book.getCoverPath() != null,
                book.getDownloadCount(),
                book.getUploadedBy() != null ? book.getUploadedBy().getId() : null,
                book.getUploadedBy() != null ? book.getUploadedBy().getFullName() : null,
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }
}

