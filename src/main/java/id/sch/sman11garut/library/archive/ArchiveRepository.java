package id.sch.sman11garut.library.archive;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchiveRepository extends JpaRepository<ArchiveItem, Long>, JpaSpecificationExecutor<ArchiveItem> {

    boolean existsByChecksumSha256(String checksumSha256);

    Optional<ArchiveItem> findByIdAndDeletedAtIsNull(Long id);

    Page<ArchiveItem> findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByDeletedAtIsNull();

    @Query("SELECT a.mediaType, COUNT(a) FROM ArchiveItem a WHERE a.deletedAt IS NULL GROUP BY a.mediaType")
    List<Object[]> countGroupedByMediaType();

    /** Rows: {mediaType, count, total file size} of active items. */
    @Query("SELECT a.mediaType, COUNT(a), COALESCE(SUM(a.fileSize), 0) FROM ArchiveItem a WHERE a.deletedAt IS NULL GROUP BY a.mediaType")
    List<Object[]> sumActiveFileSizeByMediaType();

    /** Rows: {mediaType, count, total file size} of soft-deleted items (still on disk). */
    @Query("SELECT a.mediaType, COUNT(a), COALESCE(SUM(a.fileSize), 0) FROM ArchiveItem a WHERE a.deletedAt IS NOT NULL GROUP BY a.mediaType")
    List<Object[]> sumDeletedFileSizeByMediaType();

    @Modifying
    @Query("UPDATE ArchiveItem a SET a.viewCount = a.viewCount + 1 WHERE a.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE ArchiveItem a SET a.thumbnailPath = :thumb, a.width = :w, a.height = :h, a.durationSeconds = :dur WHERE a.id = :id")
    void updateMediaMetadata(
            @Param("id") Long id,
            @Param("thumb") String thumb,
            @Param("w") Integer w,
            @Param("h") Integer h,
            @Param("dur") Integer dur);

    @Query(value = """
            SELECT a.* FROM archive_items a
            WHERE a.deleted_at IS NULL
              AND (:staff = true OR a.visibility = 'PUBLIC')
              AND (:categoryId IS NULL OR a.category_id = :categoryId)
              AND (:mediaType IS NULL OR a.media_type = :mediaType)
              AND (:fromDate IS NULL OR a.event_date >= :fromDate)
              AND (:toDate IS NULL OR a.event_date <= :toDate)
              AND MATCH(a.title, a.description) AGAINST (:q IN NATURAL LANGUAGE MODE)
            """,
            countQuery = """
            SELECT COUNT(*) FROM archive_items a
            WHERE a.deleted_at IS NULL
              AND (:staff = true OR a.visibility = 'PUBLIC')
              AND (:categoryId IS NULL OR a.category_id = :categoryId)
              AND (:mediaType IS NULL OR a.media_type = :mediaType)
              AND (:fromDate IS NULL OR a.event_date >= :fromDate)
              AND (:toDate IS NULL OR a.event_date <= :toDate)
              AND MATCH(a.title, a.description) AGAINST (:q IN NATURAL LANGUAGE MODE)
            """,
            nativeQuery = true)
    Page<ArchiveItem> searchFulltext(
            @Param("q") String q,
            @Param("categoryId") Integer categoryId,
            @Param("mediaType") String mediaType,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("staff") boolean staff,
            Pageable pageable);
}
