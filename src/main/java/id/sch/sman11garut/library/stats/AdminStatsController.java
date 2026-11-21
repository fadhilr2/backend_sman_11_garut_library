package id.sch.sman11garut.library.stats;

import id.sch.sman11garut.library.stats.dto.AdminStatsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: Stats", description = "Librarian only")
@RequestMapping("/api/admin/stats")
@PreAuthorize("hasRole('LIBRARIAN')")
public class AdminStatsController {

    private final StatsService statsService;

    public AdminStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(summary = "Totals, downloads and top books")
    @GetMapping
    public ResponseEntity<AdminStatsDto> getStats() {
        return ResponseEntity.ok(statsService.getAdminStats());
    }
}
