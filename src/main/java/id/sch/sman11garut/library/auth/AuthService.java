package id.sch.sman11garut.library.auth;

import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.user.User;
import id.sch.sman11garut.library.user.UserDto;
import id.sch.sman11garut.library.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottlingService throttlingService;
    private final String dummyHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginThrottlingService throttlingService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttlingService = throttlingService;
        // Same encoder and cost as real hashes, so both paths take the same time.
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    public LoginResponse login(LoginRequest request) {
        if (throttlingService.isBlocked(request.username())) {
            throw AppException.badRequest(
                    "ACCOUNT_TEMPORARILY_LOCKED",
                    "Too many failed login attempts. Please try again in 5 minutes."
            );
        }

        User user = userRepository.findByUsername(request.username())
                .orElse(null);

        // Always run one BCrypt check, also for unknown usernames, so the response time
        // doesn't reveal which usernames exist.
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (user == null || !user.isActive() || !passwordMatches) {
            throttlingService.recordFailedAttempt(request.username());
            throw AppException.badRequest("INVALID_CREDENTIALS", "Invalid username or password");
        }

        throttlingService.resetAttempts(request.username());
        String token = jwtService.generateToken(user);
        return new LoginResponse(token, UserDto.from(user));
    }

    @Transactional(readOnly = true)
    public UserDto getMe(String username) {
        User user = userRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> AppException.notFound("User not found or account is deactivated"));
        return UserDto.from(user);
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> AppException.notFound("User not found or account is deactivated"));

        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw AppException.badRequest("INVALID_OLD_PASSWORD", "Old password does not match");
        }

        if (request.oldPassword().equals(request.newPassword())) {
            throw AppException.badRequest("SAME_PASSWORD", "New password cannot be the same as the old password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }
}

