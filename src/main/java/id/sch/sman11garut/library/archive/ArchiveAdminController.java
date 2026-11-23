package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.common.PurgeResultDto;
import id.sch.sman11garut.library.common.TrashItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Operation(summary = "List archive items in the trash, most recently deleted first")
    @GetMapping("/trash")
    public ResponseEntity<PageResponse<TrashItemDto>> listTrash(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(archiveService.listTrash(pageable));
    }

    @Operation(summary = "Permanently delete one archive item from the trash (row, file and thumbnail)")
    @DeleteMapping("/trash/{id}")
    public ResponseEntity<Void> purgeArchiveItem(@PathVariable Long id) {
        archiveService.purgeArchiveItem(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Empty the archive trash")
    @DeleteMapping("/trash")
    public ResponseEntity<PurgeResultDto> emptyTrash() {
        return ResponseEntity.ok(new PurgeResultDto(archiveService.emptyTrash()));
    }
}
