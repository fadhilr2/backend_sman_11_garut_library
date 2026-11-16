package id.sch.sman11garut.library.archive;

import java.time.LocalDate;

public record ArchiveSearchCriteria(
        String q,
        Integer categoryId,
        MediaType mediaType,
        LocalDate from,
        LocalDate to,
        String sort
) {}
