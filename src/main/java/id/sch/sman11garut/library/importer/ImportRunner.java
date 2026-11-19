package id.sch.sman11garut.library.importer;

import id.sch.sman11garut.library.archive.ArchiveMetadataRequest;
import id.sch.sman11garut.library.archive.ArchiveService;
import id.sch.sman11garut.library.book.BookMetadataRequest;
import id.sch.sman11garut.library.book.BookService;
import id.sch.sman11garut.library.category.CategoryService;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.importer.ImportCsvReader.CsvRow;
import id.sch.sman11garut.library.importer.ImportRowMapper.ArchiveRow;
import id.sch.sman11garut.library.importer.ImportRowMapper.BookRow;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs one import job on the single-thread {@code importExecutor}.
 *
 * <p>Each row goes through the normal {@link BookService}/{@link ArchiveService} create path
 * (its own transaction), so storage, type checks, duplicate detection, compensation and
 * thumbnail events behave exactly like a manual upload. There is deliberately no transaction
 * around the loop: one bad row never rolls back the others.
 *
 * <p>Kept separate from {@link ImportService} so the {@code @Async} call goes through the proxy.
 */
@Component
public class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    static final int MAX_ERROR_LINES = 1000;

    private final ImportJobRepository jobRepository;
    private final BookService bookService;
    private final ArchiveService archiveService;
    private final CategoryService categoryService;
    private final Validator validator;

    public ImportRunner(
            ImportJobRepository jobRepository,
            BookService bookService,
            ArchiveService archiveService,
            CategoryService categoryService,
            Validator validator) {
        this.jobRepository = jobRepository;
        this.bookService = bookService;
        this.archiveService = archiveService;
        this.categoryService = categoryService;
        this.validator = validator;
    }

    @Async("importExecutor")
    public void run(Long jobId, Path folder) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Import job {} disappeared before it started", jobId);
            return;
        }
        log.info("Import job {} started: target={}, folder={}", jobId, job.getTarget(), folder);

        ErrorReport errors = new ErrorReport();
        try {
            List<CsvRow> rows = ImportCsvReader.read(folder.resolve(job.getTarget().csvFileName()),
                    ImportRowMapper.REQUIRED_COLUMNS);
            job.setTotalRows(rows.size());
            job = jobRepository.save(job);

            for (CsvRow row : rows) {
                String filename = row.get("filename");
                try {
                    importRow(job.getTarget(), row, folder);
                    job.setSucceeded(job.getSucceeded() + 1);
                } catch (AppException ex) {
                    if ("DUPLICATE_FILE".equals(ex.getCode())) {
                        job.setSkipped(job.getSkipped() + 1);
                    } else {
                        job.setFailed(job.getFailed() + 1);
                        errors.add(row.rowNumber(), filename, ex.getMessage());
                    }
                } catch (ImportRowException ex) {
                    job.setFailed(job.getFailed() + 1);
                    errors.add(row.rowNumber(), filename, ex.getMessage());
                } catch (Exception ex) {
                    log.warn("Import job {} row {} failed", jobId, row.rowNumber(), ex);
                    job.setFailed(job.getFailed() + 1);
                    errors.add(row.rowNumber(), filename, "Unexpected error: " + ex.getMessage());
                }
                job.setErrorReport(errors.text());
                job = jobRepository.save(job); // progress is visible to GET /api/admin/import/{id}
            }

            job.setStatus(ImportStatus.COMPLETED);
        } catch (Throwable ex) {
            // Whole-job failure: CSV unreadable, bad header, DB down, missing library (NoClassDefFoundError), ...
            // Catch Throwable so a job can never be left RUNNING forever.
            log.error("Import job {} failed", jobId, ex);
            errors.addGeneral(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            job.setStatus(ImportStatus.FAILED);
        }

        job.setErrorReport(errors.text());
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
        log.info("Import job {} finished: status={}, total={}, succeeded={}, skipped={}, failed={}",
                jobId, job.getStatus(), job.getTotalRows(), job.getSucceeded(), job.getSkipped(), job.getFailed());
    }

    private void importRow(ImportTarget target, CsvRow row, Path folder) throws Exception {
        if (target == ImportTarget.BOOK) {
            BookRow parsed = ImportRowMapper.toBook(row);
            Path file = resolveFile(folder, parsed.filename());
            validate(parsed.metadata());
            BookMetadataRequest metadata = parsed.metadataWithCategory(
                    categoryService.findOrCreateBookCategoryId(parsed.categoryName()));
            try (InputStream in = Files.newInputStream(file)) {
                bookService.createBookFromStream(in, file.getFileName().toString(), metadata);
            }
        } else {
            ArchiveRow parsed = ImportRowMapper.toArchive(row);
            Path file = resolveFile(folder, parsed.filename());
            validate(parsed.metadata());
            ArchiveMetadataRequest metadata = parsed.metadataWithCategory(
                    categoryService.findOrCreateArchiveCategoryId(parsed.categoryName()));
            try (InputStream in = Files.newInputStream(file)) {
                archiveService.createArchiveItemFromStream(in, file.getFileName().toString(), metadata);
            }
        }
    }

    /** The file must exist inside the target folder; absolute paths and {@code ..} are rejected. */
    static Path resolveFile(Path folder, String filename) {
        Path base = folder.toAbsolutePath().normalize();
        Path file;
        try {
            file = base.resolve(filename.replace('\\', '/')).normalize();
        } catch (RuntimeException ex) {
            throw new ImportRowException("Invalid file name '" + filename + "'");
        }
        if (!file.startsWith(base) || file.equals(base)) {
            throw new ImportRowException("File '" + filename + "' must be inside the import folder");
        }
        if (!Files.isRegularFile(file)) {
            throw new ImportRowException("File not found in the import folder");
        }
        return file;
    }

    private <T> void validate(T metadata) {
        Set<ConstraintViolation<T>> violations = validator.validate(metadata);
        if (!violations.isEmpty()) {
            throw new ImportRowException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    /** Collects error lines, capped so a huge broken CSV cannot blow up the MEDIUMTEXT column. */
    private static final class ErrorReport {
        private final StringBuilder text = new StringBuilder();
        private int lines;
        private boolean truncated;

        void add(int rowNumber, String filename, String message) {
            append("Row " + rowNumber + (filename != null ? " (" + filename + ")" : "") + ": " + message);
        }

        void addGeneral(String message) {
            append("Import stopped: " + message);
        }

        private void append(String line) {
            if (lines >= MAX_ERROR_LINES) {
                if (!truncated) {
                    text.append("... more errors not shown\n");
                    truncated = true;
                }
                return;
            }
            text.append(line.replace('\n', ' ')).append('\n');
            lines++;
        }

        String text() {
            return text.isEmpty() ? null : text.toString();
        }
    }
}
