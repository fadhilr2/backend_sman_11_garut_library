package id.sch.sman11garut.library.user;

import id.sch.sman11garut.library.common.AppException;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserDto> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(UserDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("User not found with id: " + id));
        return UserDto.from(user);
    }

    @Transactional
    public UserDto createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw AppException.conflict("DUPLICATE_USERNAME", "Username '" + request.username() + "' is already in use");
        }

        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                request.resolveRole()
        );

        User saved = userRepository.save(user);
        return UserDto.from(saved);
    }

    @Transactional
    public UserDto updateUser(Long id, UpdateUserRequest request, String currentUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("User not found with id: " + id));

        // Safeguard 1: Cannot deactivate own account
        if (request.active() != null && !request.active() && user.getUsername().equalsIgnoreCase(currentUsername)) {
            throw AppException.badRequest("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account");
        }

        // Safeguard 2: Cannot deactivate the sole active librarian
        if (request.active() != null && !request.active() && user.isLibrarian()) {
            long activeLibrarians = userRepository.findAll().stream()
                    .filter(u -> u.isLibrarian() && u.isActive())
                    .count();
            if (activeLibrarians <= 1) {
                throw AppException.badRequest("CANNOT_DEACTIVATE_LAST_LIBRARIAN", "Cannot deactivate the only active librarian account");
            }
        }

        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }

        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        if (request.active() != null) {
            user.setActive(request.active());
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }

        User updated = userRepository.save(user);
        return UserDto.from(updated);
    }
}

