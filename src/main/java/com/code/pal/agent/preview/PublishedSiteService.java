package com.code.pal.agent.preview;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.brief.ProjectBriefFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The published site is a snapshot taken when the user publishes, so later agent runs change the
 * preview but not what visitors see until the next publish.
 *
 * <p>Publishing builds the project's current files with its current variables (what was reviewed is
 * what goes live), copies the result to {@code .codepal/published/<id>} and points {@code LIVE} at it;
 * the previous snapshot survives one more publish for requests already reading it. A build that fails
 * publishes nothing: the previous snapshot stays live.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedSiteService {
    static final String PUBLISHED = ProjectPaths.INTERNAL + "/published";
    static final String LIVE = "LIVE";

    private final SiteBuildService sites;
    private final ProjectFiles files;
    private final SandboxPort sandbox;

    /** @throws IllegalStateException with the reason when there is nothing publishable */
    public void publish(UUID projectId) {
        SiteBuildService.Result built = sites.build(projectId);
        if (built.error() != null) throw new IllegalStateException("The site does not build: " + built.error());
        String source = sites.currentSite(projectId);
        if (source == null) {
            if (!files.regularFile(projectId, ".", "index.html")) {
                throw new IllegalStateException("There is no index.html to publish yet");
            }
            source = ".";   // a static site: its files are the site
        }
        String id = UUID.randomUUID().toString();
        sandbox.copyTree(projectId, source, PUBLISHED + "/" + id);
        sandbox.removeTree(projectId, PUBLISHED + "/" + id + "/" + ProjectBriefFile.NAME);   // the brief is not published
        String previous = publishedSite(projectId);
        files.writeText(projectId, PUBLISHED, LIVE, id);
        Set<String> keep = new HashSet<>(List.of(id));
        if (previous != null) keep.add(previous.substring(PUBLISHED.length() + 1));
        prune(projectId, keep);
        log.info("site_published projectId={} snapshot={}", projectId, id);
    }

    public void unpublish(UUID projectId) {
        if (files.exists(projectId, PUBLISHED, LIVE)) files.delete(projectId, PUBLISHED, LIVE);
        prune(projectId, Set.of());
    }

    /** The folder visitors are served, or null when nothing is published. */
    public String publishedSite(UUID projectId) {
        try {
            if (!files.regularFile(projectId, PUBLISHED, LIVE)) return null;
            String site = PUBLISHED + "/" + UUID.fromString(files.readText(projectId, PUBLISHED, LIVE).trim());
            return files.exists(projectId, site, "index.html") ? site : null;
        } catch (RuntimeException unreadableOrInvalid) {
            return null;
        }
    }

    private void prune(UUID projectId, Set<String> keep) {
        try {
            for (ProjectFiles.Entry entry : files.list(projectId, PUBLISHED, 1)) {
                if (entry.directory() && !keep.contains(entry.path())) sandbox.removeTree(projectId, PUBLISHED + "/" + entry.path());
            }
        } catch (RuntimeException error) {
            log.warn("published_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }
}
