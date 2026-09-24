package com.code.pal.identity.service;

import com.code.pal.identity.dto.UserSummary;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only lookup of users by email or id — the port other modules use to resolve people
 * (e.g. inviting a member, showing who authored a message) without reaching into identity internals.
 */
public interface UserDirectory {

    /** Find an account by (normalized) email — used when inviting a member. */
    Optional<UserSummary> findByEmail(String email);

    /** Batch-resolve users by internal id — used to hydrate member/author lists. */
    List<UserSummary> findByIds(Collection<Long> ids);
}
