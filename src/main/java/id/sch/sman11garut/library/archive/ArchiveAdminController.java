package id.sch.sman11garut.library.archive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: Archive", description = "Librarian only")
@RequestMapping("/api/admin/archive")
@PreAuthorize("hasRole('LIBRARIAN')")
public class ArchiveAdminController {

    private final ArchiveService archiveService;

    public ArchiveAdminController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Operation(summary = "Restore a soft-deleted archive item")
    @PostMapping("/{id}/restore")
    public ResponseEntity<ArchiveDetailDto> restoreArchiveItem(@PathVariable Long id) {
        return ResponseEntity.ok(archiveService.restoreArchiveItem(id));
    }
}
