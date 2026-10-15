package com.code.pal.agent;

import com.code.pal.agent.activity.AgentActivityMapper;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.dto.AgentStepResponse;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentPublicTimelineTest {
    @Test void historicalRawThinkingContentAndDataAreNotPublic() {
        var activities=new AgentActivityMapper(new ObjectMapper());
        var step=AgentStep.builder().seq(1).type(AgentStepType.THINKING).content("PRIVATE_RAW_REASONING").data("{\"reasoning\":\"PRIVATE_REASONING_DATA\"}").build();
        var rest=AgentStepResponse.visible(step,activities).orElseThrow();
        assertEquals("Reviewing the request",rest.content());assertNull(rest.data());
    }
    @Test void aModelCannotCompletePendingPlanCriteria() {
        var ctx=new com.code.pal.agent.tool.AgentContext(UUID.randomUUID(),7L,com.code.pal.project.domain.ProjectType.BUILD,".",null);
        ctx.plan().revise(java.util.List.of("Working login"),"fixture");
        assertThrows(com.code.pal.agent.tool.AgentToolException.class,()->new com.code.pal.agent.tool.impl.UpdatePlanStepTool().execute(Map.of("stepId",1,"status","COMPLETED"),ctx));
        assertEquals(com.code.pal.agent.plan.AgentPlanState.StepStatus.PENDING,ctx.plan().snapshot().steps().getFirst().status());
    }
    @Test void restReplayAndLiveBroadcastUseTheSameAllowlistedProjection() {
        var mapper=new ObjectMapper();var activities=new AgentActivityMapper(mapper);
        var writer=mock(AgentStepWriter.class);var realtime=mock(RealtimeService.class);
        var run=AgentRun.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID()).build();
        when(writer.appendOnce(any(),any(),anyString(),anyString())).thenReturn(7);
        var data=Map.of("success",false,"path","src/App.tsx","errorCode","VERSION_MISMATCH","nested",Map.of("provider","PRIVATE"));
        new AgentEvents(writer,realtime,mapper,activities).emit(run,AgentStepType.TOOL_RESULT,"patchFile",data);
        var capture=ArgumentCaptor.forClass(RealtimeMessage.class);verify(realtime).sendToProject(eq(run.getProjectId()),capture.capture());
        var live=(Map<?,?>)capture.getValue().payload();
        var step=AgentStep.builder().seq(7).type(AgentStepType.TOOL_RESULT).content("patchFile").data(mapper.writeValueAsString(data)).build();
        var rest=AgentStepResponse.visible(step,activities).orElseThrow();
        assertEquals(mapper.readTree(rest.data()),live.get("data"));assertEquals(rest.activity(),live.get("activity"));
        assertFalse(rest.data().contains("PRIVATE"));assertEquals(false,rest.activity().ok());
        verify(writer).appendOnce(eq(run.getId()),eq(AgentStepType.TOOL_RESULT),eq("patchFile"),eq(mapper.writeValueAsString(data)));
    }
}
