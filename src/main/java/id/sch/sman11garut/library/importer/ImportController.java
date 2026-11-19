package id.sch.sman11garut.library.importer;

import id.sch.sman11garut.library.auth.UserPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/import")
@PreAuthorize("hasRole('LIBRARIAN')")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** Starts a bulk import from the server's import folder. Returns 202; poll the job for progress. */
    @PostMapping
    public ResponseEntity<ImportJobDto> startImport(
            @Valid @RequestBody StartImportRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ImportJobDto job = importService.startImport(request.target(), principal);
        return ResponseEntity.accepted()
                .location(URI.create("/api/admin/import/" + job.id()))
                .body(job);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ImportJobDto> getJob(@PathVariable Long jobId) {
        return ResponseEntity.ok(importService.getJob(jobId));
    }

    @GetMapping
    public ResponseEntity<List<ImportJobDto>> listJobs() {
        return ResponseEntity.ok(importService.listRecentJobs());
    }
}
