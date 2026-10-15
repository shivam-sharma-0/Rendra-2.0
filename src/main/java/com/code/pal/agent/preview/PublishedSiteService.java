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
import com.code.pal.agent.stack.MernPack;
import com.code.pal.agent.stack.StackPacks;
import org.springframework.beans.factory.annotation.Autowired;

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
    static final String RELEASE = "RELEASE";
    private StackPacks stacks;
    @Autowired
    void useStacks(StackPacks stacks) { this.stacks = stacks; }

    private final SiteBuildService sites;
    private final ProjectFiles files;
    private final SandboxPort sandbox;

    /** @throws IllegalStateException with the reason when there is nothing publishable */
    public void publish(UUID projectId) {
        requireStaticRelease(projectId);
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
        requireStaticRelease(projectId);
        files.writeText(projectId, PUBLISHED + "/" + id, RELEASE, "STATIC_V1");
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
            // Legacy snapshots have no server/config binding; require an explicit safe republish.
            return files.regularFile(projectId, site, RELEASE)
                    && "STATIC_V1".equals(files.readText(projectId, site, RELEASE))
                    && files.exists(projectId, site, "index.html") ? site : null;
        } catch (RuntimeException unreadableOrInvalid) {
            return null;
        }
    }

    private void requireStaticRelease(UUID projectId) {
        var capability=capability(projectId);if(!capability.available())throw new IllegalStateException(capability.reason());
    }

    public record Capability(boolean available,String reason) {}
    public Capability capability(UUID projectId) {
        if(stacks==null)return new Capability(false,"Publication capability is unavailable");
        var stack=stacks.detect(files,projectId,".");
        if(stack instanceof MernPack || files.exists(projectId,".","server/package.json"))
            return new Capability(false,"Publishing apps with an API is unavailable until isolated frontend/backend releases are supported");
        boolean entry=files.regularFile(projectId,".","index.html") || sites.currentSite(projectId)!=null || stack.previewBuild(files,projectId,".")!=null;
        return entry?new Capability(true,null):new Capability(false,"Build a browser application before publishing");
    }

    private void prune(UUID projectId, Set<String> keep) {
        // Retain snapshots until maintenance can prove no instance/request still references them.
    }
}
