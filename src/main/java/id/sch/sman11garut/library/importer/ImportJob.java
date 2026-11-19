package id.sch.sman11garut.library.importer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, columnDefinition = "ENUM('BOOK','ARCHIVE')")
    private ImportTarget target;

    /** User id of the librarian who started the job (plain id; no navigation needed). */
    @Column(name = "started_by")
    private Long startedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "ENUM('RUNNING','COMPLETED','FAILED')")
    private ImportStatus status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "succeeded", nullable = false)
    private int succeeded;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "failed", nullable = false)
    private int failed;

    /** One line per problem row, e.g. {@code Row 4 (fisika.pdf): File not found}. */
    @Column(name = "error_report", columnDefinition = "MEDIUMTEXT")
    private String errorReport;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public ImportJob() {
    }

    public ImportJob(ImportTarget target, Long startedBy) {
        this.target = target;
        this.startedBy = startedBy;
        this.status = ImportStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public Long getId() { return id; }
    public ImportTarget getTarget() { return target; }
    public Long getStartedBy() { return startedBy; }
    public ImportStatus getStatus() { return status; }
    public void setStatus(ImportStatus status) { this.status = status; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public String getErrorReport() { return errorReport; }
    public void setErrorReport(String errorReport) { this.errorReport = errorReport; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
