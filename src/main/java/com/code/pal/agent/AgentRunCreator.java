package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.agent.dispatch.AgentDispatch;
import com.code.pal.agent.dispatch.AgentDispatchRepository;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persist request identity, run and outbox in the accepting transaction, before commit. */
@Component
@RequiredArgsConstructor
public class AgentRunCreator {

    private final AgentRunRepository runRepository;
    private final MessageRepository messages;
    private final AgentDispatchRepository outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRun create(AssistantTurnRequested event) {
        var assistant = messages.findByIdAndProjectId(event.assistantMessageId(), event.projectId())
                .orElseThrow(() -> new IllegalStateException("Accepted assistant placeholder missing"));
        if (assistant.getRole() != MessageRole.ASSISTANT || assistant.getReplyToId() == null)
            throw new IllegalStateException("Accepted assistant request identity missing");
        var request = messages.lockByIdAndProjectId(assistant.getReplyToId(), event.projectId())
                .orElseThrow(() -> new IllegalStateException("Accepted user request missing"));
        if (request.getRole() != MessageRole.USER || !event.userId().equals(request.getAuthorId())
                || event.mode() != request.getMode())
            throw new IllegalStateException("Accepted user request identity mismatch");
        var existing = runRepository.findByProjectIdAndRequestMessageId(event.projectId(), request.getId());
        if (existing.isPresent()) {
            if (!event.assistantMessageId().equals(existing.get().getMessageId()))
                throw new IllegalStateException("This accepted request already has another assistant run");
            return existing.get();
        }
        AgentRun run = runRepository.save(AgentRun.builder()
                .projectId(event.projectId()).userId(event.userId()).mode(event.mode())
                .messageId(event.assistantMessageId()).requestMessageId(request.getId())
                .requestFingerprint(RequestIdentity.fingerprint(request)).status(RunStatus.QUEUED).build());
        outbox.save(new AgentDispatch(run.getId()));
        return run;
    }
}
