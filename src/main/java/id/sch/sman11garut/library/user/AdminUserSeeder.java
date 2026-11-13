package id.sch.sman11garut.library.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_USERNAME:admin}")
    private String adminUsername;

    @Value("${ADMIN_PASSWORD:admin123}")
    private String adminPassword;

    public AdminUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!userRepository.existsByRole(Role.LIBRARIAN)) {
            log.info("No LIBRARIAN account found in database. Seeding initial admin: {}", adminUsername);

            User admin = new User(
                    adminUsername,
                    passwordEncoder.encode(adminPassword),
                    "Petugas Perpustakaan",
                    Role.LIBRARIAN
            );

            userRepository.save(admin);
            log.info("Initial LIBRARIAN account created successfully with username '{}'.", adminUsername);
        }
    }
}

