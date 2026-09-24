package com.code.pal.identity.repository;

import com.code.pal.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * User persistence. Both lookups hit the unique {@code email} index, so each is an
 * O(log n) index probe — no full scans on the auth hot path.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
