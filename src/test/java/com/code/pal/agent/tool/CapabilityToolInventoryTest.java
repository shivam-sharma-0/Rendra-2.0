package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityToolInventoryTest {
    private final AgentProperties properties = new AgentProperties();
    private final LocalProject project = LocalProject.create(properties);
    private final AgentContext context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
    private final RunBehaviorCriteria criteria = mock(RunBehaviorCriteria.class);
    private final CapabilityToolInventory inventory = new CapabilityToolInventory(properties, new StackPacks(new ObjectMapper()), project.files, criteria);
    private final List<AgentTool> tools = List.of("readFile", "patchFile", "createFiles", "startProject", "runTests", "runBuild", "runTypeCheck",
            "validateChanges", "finishBuild", "refreshDependencies", "retireAcceptanceCheck", "grantAdmin").stream().map(name -> {
                var tool = mock(AgentTool.class);
                when(tool.name()).thenReturn(name);
                return tool;
            }).toList();

    private Set<String> selected() {
        return inventory.select(ToolAccessPolicy.forMode(tools, ProjectType.BUILD), context).stream().map(AgentTool::name).collect(Collectors.toSet());
    }

    @Test
    void nodeBundlesOmitOnlyUnavailableCommandsSetupAndRetirementWhileKeepingFinalValidation() {
        properties.setCompactToolCatalog(true);
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        assertEquals(Set.of("readFile", "patchFile", "createFiles", "runBuild", "validateChanges", "finishBuild", "refreshDependencies"), selected());
    }

    @Test
    void changedCapabilitiesReappearOnTheNextRequestWithoutAnExtraSelectionCall() {
        properties.setCompactToolCatalog(true);
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        assertFalse(selected().contains("runTests"));
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\",\"test\":\"vitest\"}}");
        assertTrue(selected().contains("runTests"));
    }

    @Test
    void anEmptyOrUnreadableProjectKeepsCommandsNeededAfterSameBatchStarterCreation() {
        properties.setCompactToolCatalog(true);
        assertTrue(selected().containsAll(Set.of("startProject", "runBuild", "runTypeCheck", "runTests", "refreshDependencies")));
        project.write("package.json", "malformed");
        assertTrue(selected().containsAll(Set.of("runBuild", "runTypeCheck", "runTests", "refreshDependencies")));
    }

    @Test
    void retirementExposureRequiresActualInheritedAdvisoryChecksAndNeverWeakensThem() {
        properties.setCompactToolCatalog(true);
        var recipe = new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction("heading",
                BehaviorCriteria.BrowserKind.ASSERT_TEXT, "h1", "Title")), List.of(), false, false, BehaviorCriteria.Provenance.MODEL_SPEC);
        when(criteria.regression(context)).thenReturn(recipe);
        assertTrue(selected().contains("retireAcceptanceCheck"));
        when(criteria.current(context)).thenReturn(recipe);
        assertFalse(selected().contains("retireAcceptanceCheck"));
    }

    @Test
    void compactCatalogIsOffByDefaultAndCannotGrantAnyToolOutsideModeAuthority() {
        assertTrue(selected().containsAll(Set.of("startProject", "runTests", "retireAcceptanceCheck")));
        verifyNoInteractions(criteria);
        assertFalse(selected().contains("grantAdmin"));
        assertEquals(List.of("readFile"), ToolAccessPolicy.forMode(tools, ProjectType.CHAT).stream().map(AgentTool::name).toList());
        assertEquals(List.of("readFile"), ToolAccessPolicy.forMode(tools, ProjectType.PLAN).stream().map(AgentTool::name).toList());
    }
}
