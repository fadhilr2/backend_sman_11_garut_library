package id.sch.sman11garut.library.book;

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
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    boolean existsByChecksumSha256(String checksumSha256);

    boolean existsByCategoryIdAndDeletedAtIsNull(Integer categoryId);

    Optional<Book> findByIdAndDeletedAtIsNull(Long id);

    Page<Book> findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByDeletedAtIsNull();

    List<Book> findTop5ByDeletedAtIsNullOrderByDownloadCountDesc();

    @Query("SELECT b.bookType, COUNT(b) FROM Book b WHERE b.deletedAt IS NULL GROUP BY b.bookType")
    List<Object[]> countGroupedByBookType();

    @Modifying
    @Query("UPDATE Book b SET b.downloadCount = b.downloadCount + 1 WHERE b.id = :id")
    void incrementDownloadCount(@Param("id") Long id);

    @Query(value = """
            SELECT b.* FROM books b
            WHERE b.deleted_at IS NULL
              AND (:staff = true OR b.visibility = 'PUBLIC')
              AND (:categoryId IS NULL OR b.category_id = :categoryId)
              AND (:grade IS NULL OR b.grade_level = :grade)
              AND (:type IS NULL OR b.book_type = :type)
              AND MATCH(b.title, b.author, b.description) AGAINST (:q IN NATURAL LANGUAGE MODE)
            """,
            countQuery = """
            SELECT COUNT(*) FROM books b
            WHERE b.deleted_at IS NULL
              AND (:staff = true OR b.visibility = 'PUBLIC')
              AND (:categoryId IS NULL OR b.category_id = :categoryId)
              AND (:grade IS NULL OR b.grade_level = :grade)
              AND (:type IS NULL OR b.book_type = :type)
              AND MATCH(b.title, b.author, b.description) AGAINST (:q IN NATURAL LANGUAGE MODE)
            """,
            nativeQuery = true)
    Page<Book> searchFulltext(
            @Param("q") String q,
            @Param("categoryId") Integer categoryId,
            @Param("grade") String grade,
            @Param("type") String type,
            @Param("staff") boolean staff,
            Pageable pageable);
}

