package id.sch.sman11garut.library.auth;

import id.sch.sman11garut.library.user.UserDto;

public record LoginResponse(
        String token,
        UserDto user) {
}

