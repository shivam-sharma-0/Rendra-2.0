package com.code.pal.identity.dto;

import com.code.pal.identity.domain.User;

/** Public view of a user — exposes the external {@code publicId}, never the internal id or hash. */
public record UserResponse(String id, String email, String name, String role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getPublicId().toString(), user.getEmail(), user.getName(), user.getRole().name());
    }
}
