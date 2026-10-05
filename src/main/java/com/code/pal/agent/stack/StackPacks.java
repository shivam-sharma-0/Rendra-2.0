package com.code.pal.agent.stack;

import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** The supported stacks; picks the one that best matches a project, falling back to a static site. */
@Component
public class StackPacks {

    private final List<StackPack> packs;
    private final StackPack fallback;

    @Autowired
    public StackPacks(ObjectMapper objectMapper) {
        this(List.of(new MernPack(objectMapper), new NodePack(objectMapper), new StaticSitePack()));
    }

    public StackPacks(List<StackPack> packs) {
        this.packs = List.copyOf(packs);
        this.fallback = packs.stream().filter(pack -> pack instanceof StaticSitePack).findFirst().orElse(new StaticSitePack());
    }

    /** The pack for the project under {@code root}; ties go to the earlier pack in the list. */
    public StackPack detect(ProjectFiles files, UUID projectId, String root) {
        StackPack best = fallback;
        int bestScore = 0;
        for (StackPack pack : packs) {
            int score = pack.detect(files, projectId, root);
            if (score > bestScore) {
                best = pack;
                bestScore = score;
            }
        }
        return best;
    }
}
