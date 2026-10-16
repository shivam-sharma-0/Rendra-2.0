package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.*;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatchFileRecoveryTest {
    private final LocalProject project = LocalProject.create();
    private final ChangeSetService changes = new ChangeSetService(mock(FileChangeRepository.class), mock(AgentRunRepository.class),
            new ObjectMapper(), project.properties, project.files, project.sandbox, mock(com.code.pal.project.service.ProjectService.class));
    private final PatchFileTool patch = new PatchFileTool(changes);
    private final ReadFileTool read = new ReadFileTool();
    private AgentContext context(String content) {
        project.write("a.txt", content); UUID run = UUID.randomUUID(); var plan = new AgentPlanState(); plan.revise(List.of("Patch", "Validate"), "Initial");
        return new AgentContext(project.projectId, 7L, ProjectType.BUILD, changes.start(run, project.projectId), null,
                new com.code.pal.agent.context.ProjectContextTrace(), run, plan, project.files);
    }
    private String version(AgentContext ctx) { return project.files.version(project.projectId, ctx.root(), "a.txt"); }
    private Map<String,Object> args(AgentContext ctx, String old, String next) {
        return Map.of("path", "a.txt", "expectedVersion", version(ctx), "oldText", old, "newText", next);
    }
    @Test void severalBlocksOfOneFileChangeInOneVersionCheckedCall() {
        var ctx = context("title: Old\nbody: draft\nfooter: 2025\n");
        String before = version(ctx);
        patch.execute(Map.of("path", "a.txt", "expectedVersion", before, "edits", List.of(
                Map.of("oldText", "Old", "newText", "New"), Map.of("oldText", "draft", "newText", "final"),
                Map.of("oldText", "2025", "newText", "2026"))), ctx);
        assertEquals("title: New\nbody: final\nfooter: 2026\n", project.files.readText(project.projectId, ctx.root(), "a.txt"));
        assertEquals(1, changes.view(ctx).revision(), "one write for all edits");
    }
    @Test void editsSentAsAJsonStringAreAcceptedToo() {
        var ctx = context("a=1\nb=2\n");
        Map<String, Object> args = Map.of("path", "a.txt", "expectedVersion", version(ctx),
                "edits", "[{\"oldText\":\"a=1\",\"newText\":\"a=10\"},{\"oldText\":\"b=2\",\"newText\":\"b=20\"}]");
        assertTrue(patch.validate(args).isEmpty());
        patch.execute(args, ctx);
        assertEquals("a=10\nb=20\n", project.files.readText(project.projectId, ctx.root(), "a.txt"));
    }
    @Test void aMissingEditTargetChangesNothing() {
        var ctx = context("alpha beta");
        var error = assertThrows(AgentToolException.class, () -> patch.execute(Map.of("path", "a.txt", "expectedVersion", version(ctx),
                "edits", List.of(Map.of("oldText", "alpha", "newText", "A"), Map.of("oldText", "gamma", "newText", "G"))), ctx));
        assertEquals("PATCH_TARGET_NOT_FOUND", error.code());
        assertEquals("alpha beta", project.files.readText(project.projectId, ctx.root(), "a.txt"));
        assertEquals(0, changes.view(ctx).revision());
    }
    @Test void overlappingTargetsAreAmbiguousAndNeverMutate() {
        var ctx = context("aaa");
        var error = assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "aa", "x"), ctx));
        assertEquals("PATCH_TARGET_AMBIGUOUS", error.code());
        assertEquals(version(ctx), ((Map<?,?>)error.details()).get("actualVersion"));
        assertEquals("aaa", project.files.readText(project.projectId, ctx.root(), "a.txt"));
        assertEquals(0, changes.view(ctx).revision());
    }
    @Test void failureRequiresARealBodyReadEvenWhenUnchangedAndAnAliasIsUsed() {
        var ctx = context("one unique block");
        read.execute(Map.of("path", "a.txt"), ctx);
        assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "missing", "x"), ctx));
        assertEquals("PATCH_REREAD_REQUIRED", assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "unique", "fixed"), ctx)).code());
        @SuppressWarnings("unchecked") var body = (Map<String,Object>) read.execute(Map.of("path", "./a.txt"), ctx);
        assertEquals("one unique block", body.get("content"));
        assertDoesNotThrow(() -> patch.execute(args(ctx, "unique", "fixed"), ctx));
        assertFalse(ctx.contextTrace().patchLimitReached());
        assertEquals("one fixed block", project.files.readText(project.projectId, ctx.root(), "a.txt"));
    }
    @Test void rereadsDoNotResetRepeatedMisses() {
        var ctx = context("actual"); ctx.contextTrace().patchMissLimit(2);
        for (int i=0;i<2;i++) {
            read.execute(Map.of("path", "a.txt"), ctx);
            assertEquals("PATCH_TARGET_NOT_FOUND", assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "missing", "x"), ctx)).code());
        }
        assertEquals("PATCH_STAGNATION", assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "actual", "x"), ctx)).code());
        assertEquals(0, changes.view(ctx).revision());
        assertEquals(List.of("a.txt"), ctx.contextTrace().patchBlockedPaths());
    }
    @Test void anEmptyReplacementDeletesTheUniqueBlockButAnEmptyTargetIsRefused() {
        var ctx = context("remove me");
        assertDoesNotThrow(() -> patch.execute(args(ctx, "remove ", ""), ctx));
        assertEquals("me", project.files.readText(project.projectId, ctx.root(), "a.txt"));
        assertEquals("PATCH_TARGET_EMPTY", assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "", "x"), ctx)).code());
    }
    @Test void missingVersionHasActionableCurrentVersionAndDoesNotWrite() {
        var ctx = context("actual");
        var error = assertThrows(AgentToolException.class, () -> patch.execute(Map.of("path", "a.txt", "oldText", "actual", "newText", "x"), ctx));
        assertEquals("EXPECTED_VERSION_REQUIRED", error.code());
        assertEquals(version(ctx), ((Map<?,?>)error.details()).get("actualVersion"));
        assertEquals(0, changes.view(ctx).revision());
    }
    @Test void patchMissConfigurationCannotDisableOrUnboundTheGuard() {
        var ctx = context("actual"); ctx.contextTrace().patchMissLimit(0);
        assertThrows(AgentToolException.class, () -> patch.execute(args(ctx, "missing", "x"), ctx));
        assertTrue(ctx.contextTrace().patchLimitReached());
        var other = context("actual"); other.contextTrace().patchMissLimit(999);
        for(int i=0;i<5;i++) {
            read.execute(Map.of("path", "a.txt"), other);
            assertThrows(AgentToolException.class, () -> patch.execute(args(other, "missing", "x"), other));
            assertEquals(i==4, other.contextTrace().patchLimitReached());
        }
    }
}
