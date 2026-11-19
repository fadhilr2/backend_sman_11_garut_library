package id.sch.sman11garut.library.importer;

import jakarta.validation.constraints.NotNull;

public record StartImportRequest(
        @NotNull(message = "Target is required (BOOK or ARCHIVE)")
        ImportTarget target) {
}
