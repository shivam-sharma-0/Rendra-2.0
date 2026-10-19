package com.code.pal.agent.plan;

import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient.*;
import com.code.pal.agent.tool.impl.SetPlanTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImplementationPlanningTest {
    private final ImplementationPlanning planning = new ImplementationPlanning();
    private final ModelSelection selected = new ModelSelection("openrouter", "nvidia/nemotron-3-ultra-550b-a55b:free");

    @Test
    void requestedPlannerSeesApprovedScopeAndCanOnlyProposeAPlan() {
        var result = planning.plan(selected, List.of(LlmMessage.user("Approved tasks/detail/settings routes and API scope")),
                new SetPlanTool(), Duration.ofSeconds(10), 2000, request -> {
                    assertEquals(selected.provider(), request.provider());
                    assertEquals(selected.model(), request.model());
                    assertEquals(List.of("setPlan"), request.tools().stream().map(ToolSpec::name).toList());
                    assertEquals("required", request.toolChoice());
                    assertNull(request.jsonSchema(), "The selected OpenRouter endpoint has no forced JSON format");
                    assertEquals(CallPurpose.PLANNER, LlmCallContext.current().purpose());
                    assertTrue(request.messages().stream().anyMatch(message -> message.content().contains("tasks/detail/settings")));
                    return response("setPlan", "tool_calls");
                });
        assertEquals("setPlan", result.toolCalls().getFirst().name());
    }

    @Test
    void writingAndIncompletePlanningOutputCannotReachTheExecutor() {
        for (var result : List.of(response("createFiles", "tool_calls"), response("setPlan", "length"),
                new LlmResult("Plan in prose", null, List.of(), new Usage(1, 1), "stop"))) {
            assertThrows(IllegalArgumentException.class, () -> planning.plan(selected, List.of(), new SetPlanTool(),
                    Duration.ofSeconds(10), 2000, request -> result));
        }
    }

    private LlmResult response(String tool, String finish) {
        return new LlmResult("", null, List.of(new ToolCall("plan", tool,
                "{\"steps\":[\"src/pages/Tasks.tsx lists tasks\",\"src/pages/TaskDetails.tsx edits tasks\",\"Validate routes and persistence\"]}")),
                new Usage(1, 1), finish);
    }
}
