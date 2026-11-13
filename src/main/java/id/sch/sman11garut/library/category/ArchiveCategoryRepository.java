package id.sch.sman11garut.library.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchiveCategoryRepository extends JpaRepository<ArchiveCategory, Integer> {

    Optional<ArchiveCategory> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Integer id);

    List<ArchiveCategory> findAllByOrderByNameAsc();

    List<ArchiveCategory> findByParentIsNullOrderByNameAsc();
}

