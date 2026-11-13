package id.sch.sman11garut.library.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookCategoryRepository extends JpaRepository<BookCategory, Integer> {

    Optional<BookCategory> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Integer id);

    List<BookCategory> findAllByOrderByNameAsc();

    List<BookCategory> findByParentIsNullOrderByNameAsc();

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT COUNT(*) FROM books WHERE category_id = :categoryId AND deleted_at IS NULL",
            nativeQuery = true)
    long countActiveBooks(@org.springframework.data.repository.query.Param("categoryId") Integer categoryId);
}

