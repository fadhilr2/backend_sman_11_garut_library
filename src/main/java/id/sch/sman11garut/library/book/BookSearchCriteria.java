package id.sch.sman11garut.library.book;

public record BookSearchCriteria(
        String q,
        Integer categoryId,
        GradeLevel grade,
        BookType type,
        String sort) {
}

