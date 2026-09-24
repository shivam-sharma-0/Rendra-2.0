package com.code.pal.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Invite an existing Rendra account to the workspace by email. */
public record InviteMemberRequest(
        @NotBlank @Email @Size(max = 255) String email
) {
}
