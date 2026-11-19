package id.sch.sman11garut.library.importer;

import java.time.Instant;
import java.util.List;

public record ImportJobDto(
        Long id,
        ImportTarget target,
        ImportStatus status,
        int totalRows,
        int succeeded,
        int skipped,
        int failed,
        List<String> errors,
        Long startedBy,
        Instant startedAt,
        Instant finishedAt) {

    public static ImportJobDto from(ImportJob job) {
        List<String> errors = job.getErrorReport() == null || job.getErrorReport().isBlank()
                ? List.of()
                : job.getErrorReport().lines().toList();
        return new ImportJobDto(
                job.getId(),
                job.getTarget(),
                job.getStatus(),
                job.getTotalRows(),
                job.getSucceeded(),
                job.getSkipped(),
                job.getFailed(),
                errors,
                job.getStartedBy(),
                job.getStartedAt(),
                job.getFinishedAt());
    }
}
