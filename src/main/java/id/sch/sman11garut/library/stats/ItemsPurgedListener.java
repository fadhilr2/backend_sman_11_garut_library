package id.sch.sman11garut.library.stats;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes the download logs of permanently deleted items. {@code download_logs} has no foreign
 * key to the item tables, so the app cleans them up itself. Runs synchronously inside the
 * purge transaction: if the purge rolls back, the logs stay too.
 */
@Component
public class ItemsPurgedListener {

    private final DownloadLogRepository downloadLogRepository;

    public ItemsPurgedListener(DownloadLogRepository downloadLogRepository) {
        this.downloadLogRepository = downloadLogRepository;
    }

    @EventListener
    public void onItemsPurged(ItemsPurgedEvent event) {
        if (!event.itemIds().isEmpty()) {
            downloadLogRepository.deleteByItemTypeAndItemIdIn(event.itemType(), event.itemIds());
        }
    }
}
