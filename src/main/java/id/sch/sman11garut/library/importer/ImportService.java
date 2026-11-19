package id.sch.sman11garut.library.importer;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.config.StorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportJobRepository jobRepository;
    private final ImportRunner runner;
    private final StorageProperties storageProperties;

    public ImportService(ImportJobRepository jobRepository, ImportRunner runner, StorageProperties storageProperties) {
        this.jobRepository = jobRepository;
        this.runner = runner;
        this.storageProperties = storageProperties;
    }

    /**
     * Checks the folder, records a RUNNING job and hands it to the import executor.
     * Not transactional on purpose: the job row must be committed before the async runner reads it.
     */
    public ImportJobDto startImport(ImportTarget target, UserPrincipal principal) {
        if (storageProperties.importDir() == null) {
            throw AppException.badRequest("IMPORT_DIR_NOT_CONFIGURED", "app.storage.import-dir is not configured on the server");
        }
        Path folder = storageProperties.importDir().resolve(target.folder());
        Path csv = folder.resolve(target.csvFileName());
        if (!Files.isRegularFile(csv)) {
            throw AppException.badRequest("IMPORT_CSV_NOT_FOUND",
                    "Put the files and '" + target.csvFileName() + "' in the '" + target.folder()
                            + "' folder of the import directory first");
        }
        if (jobRepository.existsByStatus(ImportStatus.RUNNING)) {
            throw AppException.conflict("IMPORT_ALREADY_RUNNING", "Another import is still running. Wait for it to finish.");
        }

        ImportJob job = jobRepository.save(new ImportJob(target, principal != null ? principal.getId() : null));
        try {
            runner.run(job.getId(), folder);
        } catch (TaskRejectedException ex) {
            job.setStatus(ImportStatus.FAILED);
            job.setErrorReport("Import stopped: the import queue is full, try again later\n");
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            throw AppException.unavailable("IMPORT_QUEUE_FULL", "The import queue is full, try again later");
        }
        return ImportJobDto.from(job);
    }

    public ImportJobDto getJob(Long id) {
        return jobRepository.findById(id)
                .map(ImportJobDto::from)
                .orElseThrow(() -> AppException.notFound("Import job not found with id: " + id));
    }

    public List<ImportJobDto> listRecentJobs() {
        return jobRepository.findTop20ByOrderByStartedAtDesc().stream().map(ImportJobDto::from).toList();
    }

    /** A job still RUNNING at startup was cut off by a crash or restart; it will never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        for (ImportJob job : jobRepository.findByStatus(ImportStatus.RUNNING)) {
            job.setStatus(ImportStatus.FAILED);
            String report = job.getErrorReport() != null ? job.getErrorReport() : "";
            job.setErrorReport(report + "Import stopped: interrupted by a server restart. Run it again; "
                    + "rows already imported will be skipped as duplicates.\n");
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            log.warn("Marked interrupted import job {} as FAILED", job.getId());
        }
    }
}
