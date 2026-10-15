package com.code.pal.project.spec;

import com.code.pal.agent.contract.ContractStore;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.capability.BuildDecisions.Auth;
import com.code.pal.project.capability.BuildDecisions.Storage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovedSpecsTest {
    private static final String CAPABILITIES = "c".repeat(64);
    private static final String TASKS = """
            {"title":"Tasks","goal":"Track my tasks","persistence":"SUPABASE","assets":[{"projectPath":"x"}],
             "pages":[{"id":"home","path":"/","title":"Tasks","featureIds":["tasks"]}],
             "entities":[{"id":"task","fields":[{"name":"title","kind":"STRING","required":true}],
                          "ownership":"USER","persistence":"SUPABASE"}],
             "features":[{"id":"tasks","kind":"LOCAL_CRUD","description":"Manage tasks","pageIds":["home"],
                          "entityIds":["task"]}],
             "theme":{"background":"#ffffff","foreground":"#111111","accent":"#ff8800","muted":"#888888",
                      "radius":"8px","spacing":"8px","font":"system"},
             "acceptance":[{"id":"empty","featureId":"tasks","kind":"BROWSER",
                            "steps":[{"kind":"ASSERT_COUNT","selector":"[data-task]","value":"0"}],"expected":"No tasks"}],
             "constraints":{"responsive":true,"accessibility":true,"exclusions":[]}}
            """;

    private final UUID project = UUID.randomUUID();
    private final UUID projection = UUID.randomUUID();
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final ContractStore contracts = mock(ContractStore.class);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final ApprovedSpecs specs = new ApprovedSpecs(memory, contracts, decisions, new ObjectMapper());

    @Test
    void theServerOwnsIdentityRevisionsPersistenceAndAssets() {
        approve(TASKS, 3, Storage.BROWSER);

        ApprovedSpec spec = specs.current(project).orElseThrow();

        assertEquals(project, spec.projectId());
        assertEquals(projection, spec.requirementsProjection());
        assertEquals(3, spec.requirementsRevision());
        assertEquals(4, spec.specRevision());
        assertEquals("sha256:" + CAPABILITIES, spec.capabilityRevision());
        assertEquals(new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE, ApprovedSpec.Storage.BROWSER,
                ApprovedSpec.Auth.NONE), spec.desired());
        assertEquals(ApprovedSpec.Storage.BROWSER, spec.spec().entities().getFirst().persistence());
        assertTrue(spec.spec().assets().isEmpty());
        assertEquals("en", spec.spec().language());
        assertEquals(spec, specs.current(project).orElseThrow(), "the same approval gives the same envelope");
    }

    @Test
    void aOnePageContentSiteWithoutDataRendersStatic() {
        String content = """
                {"title":"Bakery","goal":"A bakery page",
                 "pages":[{"id":"home","path":"/","title":"Home","featureIds":["intro"]}],"entities":[],
                 "features":[{"id":"intro","kind":"CONTENT","description":"Introduction","pageIds":["home"],"entityIds":[]}],
                 "theme":{"background":"#fff","foreground":"#111","accent":"#f80","muted":"#888","radius":"8px",
                          "spacing":"8px","font":"serif"},
                 "acceptance":[{"id":"heading","featureId":"intro","kind":"BROWSER",
                                "steps":[{"kind":"ASSERT_TEXT","selector":"h1","value":"Bakery"}],"expected":"Heading"}],
                 "constraints":{"responsive":true,"accessibility":true,"exclusions":[]}}
                """;
        approve(content, 1, Storage.NONE);

        assertEquals(ApprovedSpec.RenderStack.STATIC, specs.current(project).orElseThrow().desired().renderStack());
    }

    @Test
    void legacyStaleInvalidOrMernApprovalsHaveNoSpec() {
        approve(null, 1, Storage.BROWSER);
        assertTrue(specs.current(project).isEmpty(), "legacy approval");

        approve("[\"not a spec\"]", 1, Storage.BROWSER);
        assertTrue(specs.current(project).isEmpty(), "a draft that is not an object");

        approve(TASKS, 1, Storage.MERN);
        assertTrue(specs.current(project).isEmpty(), "MERN keeps its own contract path");

        approve(TASKS, 1, Storage.BROWSER);
        when(contracts.current(project)).thenReturn(new ContractStore.Binding(1, projection, 2, "code", CAPABILITIES, Set.of()));
        assertTrue(specs.current(project).isEmpty(), "requirements changed after approval");
    }

    private void approve(String specJson, long revision, Storage storage) {
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(projection, revision, "{}", "brief", specJson));
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1, storage, Auth.NONE, null),
                new BuildDecisions.Available(false, null), null));
        when(contracts.current(project)).thenReturn(new ContractStore.Binding(1, projection, revision, "code", CAPABILITIES, Set.of()));
    }
}
