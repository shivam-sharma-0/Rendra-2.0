package com.code.pal.identity.service.impl;

import com.code.pal.identity.domain.User;
import com.code.pal.identity.dto.UserSummary;
import com.code.pal.identity.repository.UserRepository;
import com.code.pal.identity.service.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserDirectoryImpl implements UserDirectory {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        return userRepository.findByEmail(normalized).map(UserDirectoryImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummary> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return userRepository.findAllById(ids).stream().map(UserDirectoryImpl::toSummary).toList();
    }

    private static UserSummary toSummary(User u) {
        return new UserSummary(u.getId(), u.getPublicId(), u.getEmail(), u.getName());
    }
}
