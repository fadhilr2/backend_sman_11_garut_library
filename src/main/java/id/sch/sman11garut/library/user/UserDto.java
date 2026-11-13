package id.sch.sman11garut.library.user;

import java.time.Instant;

public record UserDto(
        Long id,
        String username,
        String fullName,
        Role role,
        boolean active,
        Instant createdAt) {

    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}

