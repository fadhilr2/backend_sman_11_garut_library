package id.sch.sman11garut.library.importer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    boolean existsByStatus(ImportStatus status);

    List<ImportJob> findByStatus(ImportStatus status);

    List<ImportJob> findTop20ByOrderByStartedAtDesc();
}
