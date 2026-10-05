package com.code.pal.agent.preview;

import java.util.Map;
import java.util.UUID;

/**
 * A project's build-time variables (name to value), applied to every build of its site. They end up in
 * the built files, so they are public configuration, never secrets.
 */
public interface BuildVariables {

    Map<String, String> variables(UUID projectId);

    /** No variables: for tests and callers without a project store. */
    BuildVariables NONE = projectId -> Map.of();

    /** Published after a project's variables changed, so its site is rebuilt with them. */
    record Changed(UUID projectId) {}
}
