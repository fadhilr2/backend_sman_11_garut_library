package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.Visibility;
import java.time.LocalDate;

public record UpdateArchiveRequest(
        String title,
        String description,
        Integer categoryId,
        LocalDate eventDate,
        Visibility visibility
) {}
