package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ArchiveMetadataRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        String description,

        Integer categoryId,

        LocalDate eventDate,

        Visibility visibility
) {
    public Visibility resolveVisibility() {
        return visibility != null ? visibility : Visibility.STAFF;
    }
}
