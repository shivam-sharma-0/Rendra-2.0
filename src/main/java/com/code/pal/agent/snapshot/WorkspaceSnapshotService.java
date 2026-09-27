package com.code.pal.agent.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Creates a local Git object for the exact pre-run workspace state and can restore it after a
 * failed BUILD run. The normal Git index and user branches are never staged, committed or reset:
 * a temporary index is used solely to build an internal {@code refs/rendra/snapshots/*} object.
 */
@Slf4j
@Service
public class WorkspaceSnapshotService {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final String SNAPSHOT_PREFIX = "refs/rendra/snapshots/";

    public String createSnapshot(Path workspace, UUID runId) {
        ensureRepository(workspace);
        Path temporaryIndex = createTemporaryIndex();
        try {
            // Stage everything into the isolated index (`git add` produces no useful stdout).
            git(workspace, Map.of("GIT_INDEX_FILE", temporaryIndex.toString()), "add", "-A", "--", ".");
            String tree = git(workspace, Map.of("GIT_INDEX_FILE", temporaryIndex.toString()), "write-tree").trim();
            List<String> commit = new ArrayList<>(List.of("-c", "user.name=Rendra", "-c", "user.email=rendra@local", "commit-tree", tree,
                    "-m", "Rendra workspace snapshot " + runId));
            String head = tryGit(workspace, Map.of("GIT_INDEX_FILE", temporaryIndex.toString()), "rev-parse", "--verify", "HEAD");
            if (head != null && !head.isBlank()) commit.addAll(List.of("-p", head.trim()));
            String objectId = git(workspace, Map.of("GIT_INDEX_FILE", temporaryIndex.toString()), commit.toArray(String[]::new)).trim();
            String ref = SNAPSHOT_PREFIX + runId;
            git(workspace, Map.of(), "update-ref", ref, objectId);
            return ref;
        } finally {
            try { Files.deleteIfExists(temporaryIndex); } catch (IOException ignored) { }
        }
    }

    /** Restore tracked and non-ignored files to the internal snapshot. Intended for a failed run or explicit rollback. */
    public void restore(Path workspace, String snapshotRef) {
        if (snapshotRef == null || !snapshotRef.startsWith(SNAPSHOT_PREFIX)) {
            throw new IllegalArgumentException("Invalid workspace snapshot reference");
        }
        Path temporaryIndex = createTemporaryIndex();
        try {
            Map<String, String> env = Map.of("GIT_INDEX_FILE", temporaryIndex.toString());
            git(workspace, env, "read-tree", snapshotRef);
            git(workspace, env, "checkout-index", "--all", "--force");
            // Agent writes are non-ignored files; clean only those, preserving ignored local secrets.
            git(workspace, env, "clean", "-fd");
            log.info("Workspace restored from {}", snapshotRef);
        } finally {
            try { Files.deleteIfExists(temporaryIndex); } catch (IOException ignored) { }
        }
    }

    private void ensureRepository(Path workspace) {
        String topLevel = tryGit(workspace, Map.of(), "rev-parse", "--show-toplevel");
        Path expected = workspace.toAbsolutePath().normalize();
        Path actual = null;
        if (topLevel != null && !topLevel.isBlank()) {
            try { actual = Path.of(topLevel.trim()).toAbsolutePath().normalize(); }
            catch (RuntimeException ignored) { }
        }
        // A workspace can live under the application's own repository (and often under an ignored
        // workspaces/ or target/ directory). It needs its own repository or a parent .gitignore can
        // make the snapshot incomplete.
        if (!expected.equals(actual)) {
            git(workspace, Map.of(), "init", "--quiet");
        }
    }

    private static Path createTemporaryIndex() {
        try {
            // GIT_INDEX_FILE must point to an absent file for Git to initialize a new index.
            // An existing zero-byte temp file is interpreted as a corrupt index.
            Path path = Files.createTempFile("rendra-git-index-", ".tmp");
            Files.delete(path);
            return path;
        }
        catch (IOException e) { throw new IllegalStateException("Could not create temporary Git index", e); }
    }

    private String tryGit(Path workspace, Map<String, String> environment, String... args) {
        try { return git(workspace, environment, args); }
        catch (IllegalStateException ignored) { return null; }
    }

    private String git(Path workspace, Map<String, String> environment, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().putAll(environment);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Git is required for workspace snapshots", e);
        }

        // Drain stdout on a dedicated thread and read all the way to EOF. If we stopped reading early
        // (as a fixed readNBytes does), a command whose output exceeds the OS pipe buffer would block
        // writing while we sit in waitFor â€” a deadlock until the timeout kills it.
        OutputCapture capture = new OutputCapture(process.getInputStream());
        Thread drain = new Thread(capture, "git-io");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git command timed out");
            }
            drain.join(1_000);
            String output = capture.text();
            if (process.exitValue() != 0) throw new IllegalStateException("Git command failed: " + output.trim());
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted", e);
        }
    }

    /** Reads a process stream fully to EOF (so the child never blocks on a full pipe), retaining a bounded prefix. */
    private static final class OutputCapture implements Runnable {
        private static final int MAX_CAPTURED_BYTES = 64 * 1024;

        private final InputStream in;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();

        private OutputCapture(InputStream in) { this.in = in; }

        @Override
        public void run() {
            try (InputStream stream = in) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = MAX_CAPTURED_BYTES - kept.size();
                    if (remaining > 0) kept.write(buffer, 0, Math.min(read, remaining));
                    // Past the cap we keep reading and discard, so the pipe can never fill up.
                }
            } catch (IOException e) {
                log.debug("Git output drain ended early: {}", e.getMessage());
            }
        }

        private String text() { return kept.toString(StandardCharsets.UTF_8); }
    }
}
