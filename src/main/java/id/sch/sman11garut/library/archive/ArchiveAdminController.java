package id.sch.sman11garut.library.archive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/archive")
@PreAuthorize("hasRole('LIBRARIAN')")
public class ArchiveAdminController {

    private final ArchiveService archiveService;

    public ArchiveAdminController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ArchiveDetailDto> restoreArchiveItem(@PathVariable Long id) {
        return ResponseEntity.ok(archiveService.restoreArchiveItem(id));
    }
}
