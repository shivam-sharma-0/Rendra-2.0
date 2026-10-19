package com.code.pal.agent.starter;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.ApprovedSpec.RenderStack;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticStarterTest {
    @Test
    void preparedContextDistinguishesExistingEditableFilesFromTheMissingApplicationBody() {
        var project = LocalProject.create();
        Starter.VITE_REACT.files("Fixture").forEach(project::write);
        var context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
        var prepared = AutomaticStarter.context(context, Starter.VITE_REACT);
        var versions = (Map<?, ?>) prepared.get("preparedVersions");
        assertEquals(project.files.version(project.projectId, ".", "src/index.css"), versions.get("src/index.css"));
        assertFalse(versions.containsKey("src/App.tsx"));
        assertTrue(((Map<?, ?>) prepared.get("entryFiles")).containsKey("src/index.css"));
    }

    @Test
    void approvedStackAndExplicitUserChoicesOverrideOnlyTheNewAppDefault() {
        var project = LocalProject.create();
        var context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
        var choice = new BuildDecisions.Choice(1, BuildDecisions.Storage.NONE, BuildDecisions.Auth.NONE, null);
        var staticStack = RenderStack.STATIC;
        var vite = RenderStack.VITE;
        assertEquals(Starter.STATIC_SITE, AutomaticStarter.select(context, choice, "Build my site", staticStack, vite));
        assertEquals(Starter.VITE_REACT, AutomaticStarter.select(context, choice, "Build a React site", staticStack, staticStack));
        assertEquals(Starter.STATIC_SITE, AutomaticStarter.select(context, choice, "Build my site", null, staticStack));
    }
    @Test void onlyExplicitApprovedFreshStacksArePrepared() {
        var p=LocalProject.create();var ctx=new AgentContext(p.projectId,7L,ProjectType.BUILD,".",p.files);
        var browser=new BuildDecisions.Choice(1,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,null);
        assertEquals(Starter.VITE_REACT,AutomaticStarter.select(ctx,browser,"Create a React todo app"));
        assertEquals(Starter.VITE_REACT,AutomaticStarter.select(ctx,browser,"Create a todo app"));
        assertEquals(Starter.STATIC_SITE,AutomaticStarter.select(ctx,browser,"Create a plain HTML app without React"));
        assertEquals(Starter.MERN,AutomaticStarter.select(ctx,new BuildDecisions.Choice(1,BuildDecisions.Storage.MERN,BuildDecisions.Auth.NONE,null),"Build todo"));
        assertNull(AutomaticStarter.select(ctx,null,"Build React"));p.write("index.html","existing");assertNull(AutomaticStarter.select(ctx,browser,"Build React"));
    }
}
