package com.code.pal.agent.starter;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticStarterTest {
    @Test void onlyExplicitApprovedFreshStacksArePrepared() {
        var p=LocalProject.create();var ctx=new AgentContext(p.projectId,7L,ProjectType.BUILD,".",p.files);
        var browser=new BuildDecisions.Choice(1,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,null);
        assertEquals(Starter.VITE_REACT,AutomaticStarter.select(ctx,browser,"Create a React todo app"));
        assertNull(AutomaticStarter.select(ctx,browser,"Create a todo app"));assertNull(AutomaticStarter.select(ctx,browser,"Create a plain HTML app without React"));
        assertEquals(Starter.MERN,AutomaticStarter.select(ctx,new BuildDecisions.Choice(1,BuildDecisions.Storage.MERN,BuildDecisions.Auth.NONE,null),"Build todo"));
        assertNull(AutomaticStarter.select(ctx,null,"Build React"));p.write("index.html","existing");assertNull(AutomaticStarter.select(ctx,browser,"Build React"));
    }
}
