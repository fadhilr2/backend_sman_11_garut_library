package id.sch.sman11garut.library.book;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BookMetadataRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @Size(max = 255)
        String author,

        @Size(max = 255)
        String publisher,

        Integer publicationYear,

        String description,

        BookType bookType,

        Integer categoryId,

        GradeLevel gradeLevel,

        String curriculum,

        Visibility visibility) {

    public BookType resolveBookType() {
        return bookType != null ? bookType : BookType.EBOOK;
    }

    public GradeLevel resolveGradeLevel() {
        return gradeLevel != null ? gradeLevel : GradeLevel.ALL;
    }

    public Visibility resolveVisibility() {
        return visibility != null ? visibility : Visibility.PUBLIC;
    }
}

