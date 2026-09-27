package com.code.pal.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login payload. Validated at the controller boundary; bounds guard against oversized input. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 72) String password   // 72 = BCrypt's max effective length
) {
}
