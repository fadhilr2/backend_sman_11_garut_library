package id.sch.sman11garut.library.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByUsernameAndActiveTrue(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(Role role);

    boolean existsByRoleAndActiveTrue(Role role);

    List<User> findAllByOrderByCreatedAtDesc();
}

