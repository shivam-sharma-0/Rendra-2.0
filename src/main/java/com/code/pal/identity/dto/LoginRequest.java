package com.code.pal.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** Login payload. */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
