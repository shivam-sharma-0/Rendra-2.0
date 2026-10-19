package com.code.pal.agent.plan;

import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmClient.*;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** One planning turn on the PLAN route; its only advertised operation is the in-memory plan. */
@Component
public final class ImplementationPlanning {
    public LlmResult plan(ModelSelection model, List<LlmMessage> context, AgentTool planTool,
            Duration budget, int outputTokens, Function<LlmRequest, LlmResult> checkedCall) {
        if (!"setPlan".equals(planTool.name())) throw new IllegalArgumentException("Planning requires the plan-only tool");
        var messages = new ArrayList<>(context);
        messages.add(LlmMessage.user(PromptLoader.load(Prompt.IMPLEMENTATION_PLANNING)));
        var tool = new ToolSpec(planTool.name(), planTool.description(), planTool.parametersSchema());
        var request = new LlmRequest(model.provider(), model.model(), List.copyOf(messages), List.of(tool),
                0.0, budget, "required", outputTokens);
        LlmResult result;
        try (var purpose = LlmCallContext.purpose(CallPurpose.PLANNER, true)) {
            result = checkedCall.apply(request);
        }
        if (result == null || "length".equals(result.finishReason()) || result.toolCalls() == null
                || result.toolCalls().size() != 1 || !"setPlan".equals(result.toolCalls().getFirst().name())
                || result.toolCalls().getFirst().arguments() == null
                || result.toolCalls().getFirst().arguments().length() > 16_000) {
            throw new IllegalArgumentException("Planning must return one complete setPlan operation");
        }
        return result;
    }
}
