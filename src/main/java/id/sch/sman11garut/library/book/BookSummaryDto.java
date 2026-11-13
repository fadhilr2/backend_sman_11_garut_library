package id.sch.sman11garut.library.book;

import java.time.Instant;

public record BookSummaryDto(
        Long id,
        String title,
        String author,
        String publisher,
        Integer publicationYear,
        BookType bookType,
        Integer categoryId,
        String categoryName,
        GradeLevel gradeLevel,
        String curriculum,
        Visibility visibility,
        boolean hasCover,
        int downloadCount,
        Instant createdAt) {

    public static BookSummaryDto from(Book book) {
        return new BookSummaryDto(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPublisher(),
                book.getPublicationYear() != null ? book.getPublicationYear().intValue() : null,
                book.getBookType(),
                book.getCategory() != null ? book.getCategory().getId() : null,
                book.getCategory() != null ? book.getCategory().getName() : null,
                book.getGradeLevel(),
                book.getCurriculum(),
                book.getVisibility(),
                book.getCoverPath() != null,
                book.getDownloadCount(),
                book.getCreatedAt()
        );
    }
}

