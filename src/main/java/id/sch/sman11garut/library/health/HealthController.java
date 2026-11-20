package id.sch.sman11garut.library.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** 200 for UP and DEGRADED (the app still serves browsing), 503 when the database is down. */
    @GetMapping
    public ResponseEntity<HealthDto> health() {
        HealthDto health = healthService.check();
        HttpStatus status = "DOWN".equals(health.status()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(health);
    }
}
