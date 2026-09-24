package com.code.pal.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Signup payload. Validated at the controller boundary before any work is done. */
public record SignUpRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password,   // 72 = BCrypt's max effective length
        @Size(max = 100) String name
) {
}
