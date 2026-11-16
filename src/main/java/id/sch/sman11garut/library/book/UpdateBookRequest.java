package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.common.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBookRequest(
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
}

