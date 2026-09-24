package com.code.pal.identity.dto;

import java.util.UUID;

/**
 * Minimal user view shared with other modules (e.g. workspace member listings).
 * {@code id} is the internal scoping key; {@code publicId} is the only id safe to expose to clients.
 */
public record UserSummary(Long id, UUID publicId, String email, String name) {
}
