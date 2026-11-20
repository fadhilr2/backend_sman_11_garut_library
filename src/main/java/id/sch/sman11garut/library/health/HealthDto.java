package id.sch.sman11garut.library.health;

/**
 * Public health summary. Holds no byte counts because anyone on the Wi-Fi can call it.
 *
 * @param status {@code UP}, {@code DEGRADED} (browsing works, files don't) or {@code DOWN}
 * @param database {@code UP} or {@code DOWN}
 * @param storage {@code UP}, {@code LOW_SPACE} or {@code UNAVAILABLE}
 */
public record HealthDto(String status, String database, String storage) {
}
