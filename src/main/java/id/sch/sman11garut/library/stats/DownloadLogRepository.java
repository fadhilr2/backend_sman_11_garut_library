package id.sch.sman11garut.library.stats;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DownloadLogRepository extends JpaRepository<DownloadLog, Long> {

    long countByDownloadedAtAfter(Instant after);

    long countByItemType(ItemType itemType);

    long countByItemTypeAndDownloadedAtAfter(ItemType itemType, Instant after);

    @Modifying
    @Query("DELETE FROM DownloadLog d WHERE d.itemType = :itemType AND d.itemId IN :itemIds")
    int deleteByItemTypeAndItemIdIn(@Param("itemType") ItemType itemType, @Param("itemIds") Collection<Long> itemIds);

    @Query(value = """
            SELECT DATE(downloaded_at) as log_date, COUNT(*) as count
            FROM download_logs
            WHERE downloaded_at >= :since
            GROUP BY DATE(downloaded_at)
            ORDER BY log_date ASC
            """, nativeQuery = true)
    List<Object[]> getDailyDownloadCounts(@Param("since") Instant since);
}
