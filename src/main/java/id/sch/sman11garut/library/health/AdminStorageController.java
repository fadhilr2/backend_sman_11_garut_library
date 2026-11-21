package id.sch.sman11garut.library.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: Storage", description = "Librarian only")
@RequestMapping("/api/admin/storage")
@PreAuthorize("hasRole('LIBRARIAN')")
public class AdminStorageController {

    private final StorageUsageService storageUsageService;

    public AdminStorageController(StorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @Operation(summary = "Disk usage and storage breakdown")
    @GetMapping
    public ResponseEntity<StorageUsageDto> getStorage() {
        return ResponseEntity.ok(storageUsageService.getUsage());
    }
}
