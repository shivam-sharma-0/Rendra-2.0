package com.code.pal.project.service;

import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.project.domain.ProjectEnvironmentVariable;
import com.code.pal.project.exception.InvalidEnvironmentVariableException;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Build-time variables of a generated project (for Vite: {@code VITE_*}, read with
 * {@code import.meta.env.NAME}). They are written into the build and served to every visitor, so they
 * hold public configuration such as an API base URL or a Supabase anon key, never server secrets.
 */
@Service
@RequiredArgsConstructor
public class ProjectEnvironmentService {
    private static final Pattern NAME = Pattern.compile("^VITE_[A-Z0-9_]{1,59}$");
    private static final int MAX_VALUE = 4_000;
    private static final int MAX_VARIABLES = 50;

    private final ProjectService projects;
    private final ProjectEnvironmentVariableRepository variables;
    private final ApplicationEventPublisher events;

    public record Variable(String name, String value) {}

    @Transactional(readOnly = true)
    public List<Variable> list(Long userId, UUID projectId) {
        projects.requireCanEdit(userId, projectId);
        return variables.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(variable -> new Variable(variable.getName(), variable.getValue())).toList();
    }

    @Transactional
    public Variable set(Long userId, UUID projectId, String name, String value) {
        projects.requireCanEdit(userId, projectId);
        validate(name, value);
        ProjectEnvironmentVariable variable = variables.findByProjectIdAndName(projectId, name).orElse(null);
        if (variable == null) {
            if (variables.countByProjectId(projectId) >= MAX_VARIABLES) {
                throw new InvalidEnvironmentVariableException("A project can have at most " + MAX_VARIABLES + " variables");
            }
            variable = ProjectEnvironmentVariable.builder().projectId(projectId).name(name).value(value).build();
        } else {
            variable.setValue(value);
        }
        variables.save(variable);
        events.publishEvent(new BuildVariables.Changed(projectId));
        return new Variable(name, value);
    }

    @Transactional
    public void delete(Long userId, UUID projectId, String name) {
        projects.requireCanEdit(userId, projectId);
        variables.findByProjectIdAndName(projectId, name).ifPresent(variable -> {
            variables.delete(variable);
            events.publishEvent(new BuildVariables.Changed(projectId));
        });
    }

    /**
     * Names follow Vite's public prefix. Values are written into a dotenv file in single quotes, which
     * dotenv reads literally: a quote or a line break there would end the value early.
     */
    private static void validate(String name, String value) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidEnvironmentVariableException("Variable names look like VITE_API_URL: VITE_ then upper-case letters, digits or _");
        }
        if (value == null || value.length() > MAX_VALUE) {
            throw new InvalidEnvironmentVariableException("A value is required and at most " + MAX_VALUE + " characters");
        }
        for (char character : value.toCharArray()) {
            if (character == '\'' || character == '\n' || character == '\r' || Character.isISOControl(character)) {
                throw new InvalidEnvironmentVariableException("Values cannot contain quotes ('), line breaks or control characters");
            }
        }
        if (com.code.pal.project.supabase.SupabaseKeys.secret(value)) {
            // Every VITE_ value is compiled into the public site.
            throw new InvalidEnvironmentVariableException(com.code.pal.project.supabase.SupabaseKeys.SECRET_KEY);
        }
    }
}
