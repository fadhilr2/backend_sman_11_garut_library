package id.sch.sman11garut.library.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "Service status")
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** 200 for UP and DEGRADED (the app still serves browsing), 503 when the database is down. */
    @Operation(summary = "Health: UP, DEGRADED (storage unavailable/low) or DOWN (database)")
    @GetMapping
    public ResponseEntity<HealthDto> health() {
        HealthDto health = healthService.check();
        HttpStatus status = "DOWN".equals(health.status()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(health);
    }
}
