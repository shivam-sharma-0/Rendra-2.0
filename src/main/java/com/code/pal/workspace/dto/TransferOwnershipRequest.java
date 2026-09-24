package com.code.pal.workspace.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Transfer primary ownership to an existing member (by their membership id). */
public record TransferOwnershipRequest(
        @NotNull UUID memberId
) {
}
