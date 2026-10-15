package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.agent.llm.LlmClient;

/** Configured request caps are separate from quota reservations, which use an input estimate. */
final class VerifiedTokenBounds {
    private VerifiedTokenBounds() {}
    static long input(AgentProperties properties,LlmClient.LlmRequest request) {
        String key=request.provider().toLowerCase(java.util.Locale.ROOT)+":"+request.model();
        var bound=properties.getAccountingBounds().get(key);
        if(bound==null || bound.getInputTokens()<=0 || bound.getOutputTokens()<=0
                || bound.getEvidence()==null || bound.getEvidence().isBlank())
            throw new LlmAccountingException("Verified token limits are not configured for this provider/model");
        if(request.maxTokens()==null || request.maxTokens()<=0 || request.maxTokens()>bound.getOutputTokens())
            throw new LlmAccountingException("Requested output exceeds the verified model limit");
        boolean images=request.messages()!=null && request.messages().stream().anyMatch(m->m.images()!=null && !m.images().isEmpty());
        if(images && !bound.isImages()) throw new LlmAccountingException("Image accounting is not verified for this provider/model");
        return bound.getInputTokens();
    }
    static long reservationInput(AgentProperties properties,LlmClient.LlmRequest request) {
        long limit=input(properties,request),raw=com.code.pal.agent.llm.TokenEstimate.input(request);
        if(raw>limit)throw new LlmAccountingException("Request input estimate exceeds the configured model limit");
        return Math.min(limit,Math.addExact(raw,Math.addExact(raw/4,64)));
    }
}
