package id.sch.sman11garut.library.user;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        String fullName,

        @Size(min = 6, message = "Password must be at least 6 characters if provided")
        String password,

        Boolean active,

        Role role) {
}

