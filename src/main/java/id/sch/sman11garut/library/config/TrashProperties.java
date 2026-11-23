package id.sch.sman11garut.library.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Trash settings ({@code app.trash.*}).
 *
 * @param autoPurge when true, the nightly {@code TrashPurgeJob} permanently deletes items that
 *     have been in the trash longer than {@code retention}
 * @param retention how long a soft-deleted item stays restorable before the nightly purge
 * @param purgeCron when the nightly purge runs (server time zone)
 */
@ConfigurationProperties(prefix = "app.trash")
public record TrashProperties(
        @DefaultValue("true") boolean autoPurge,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("0 30 2 * * *") String purgeCron) {

    /** When the nightly job will purge an item deleted at {@code deletedAt}, or null if it never will. */
    public Instant purgeAt(Instant deletedAt) {
        return autoPurge && deletedAt != null ? deletedAt.plus(retention) : null;
    }
}
