package com.code.pal.agent.change;

import com.code.pal.agent.AgentRunCreator;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.exception.*;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.service.ProjectService;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class FailedDraftRetryService {
    public record Request(@NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String id,
                          @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String aiMessageId) {}
    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final MessageRepository messages;
    private final MessageService turns;
    private final AgentRunCreator creator;
    private final FailedDraftRepository repository;
    private final FailedDraftService drafts;
    public FailedDraftRetryService(ProjectService projects,AgentRunRepository runs,MessageRepository messages,MessageService turns,
                                   AgentRunCreator creator,FailedDraftRepository repository,FailedDraftService drafts) {
        this.projects=projects;this.runs=runs;this.messages=messages;this.turns=turns;this.creator=creator;this.repository=repository;this.drafts=drafts;
    }
    /** Request, assistant, seeded-run identity and dispatch intent commit together. No model is invoked here. */
    @Transactional
    public AgentRun retry(Long user,UUID project,UUID sourceId,Request request) {
        projects.requireCanEdit(user,project);
        var source=runs.findByIdAndProjectId(sourceId,project).orElseThrow(()->new AgentRunNotFoundException("Agent run not found"));
        if(!FailedDraftService.eligible(source)) throw new AgentRunConflictException("Only an unsaved failed or stopped build can be retried");
        repository.lock(project);
        var stored=repository.find(project,sourceId);
        if(stored!=null && stored.retryRunId()!=null) {
            var retry=runs.findByIdAndProjectId(stored.retryRunId(),project).orElseThrow(()->new AgentRunConflictException("Retry history unavailable"));
            if(Objects.equals(retry.getRequestMessageId(),request.id()) && Objects.equals(retry.getMessageId(),request.aiMessageId())
                    && Objects.equals(retry.getUserId(),user) && Objects.equals(retry.getRetrySourceRunId(),sourceId)) return retry;
            throw new AgentRunConflictException("A retry already exists for this draft. Open its result or retry the latest failed build.");
        }
        drafts.retryManifest(project,sourceId);
        if(!runs.findByProjectIdAndStatusIn(project,List.of(RunStatus.RUNNING,RunStatus.QUEUED,RunStatus.COMMITTING)).isEmpty())
            throw new AgentRunConflictException("Wait for the active build to finish before retrying");
        var original=messages.findByIdAndProjectId(source.getRequestMessageId(),project)
                .orElseThrow(()->new AgentRunConflictException("Original build request unavailable"));
        String prompt="Retry this build from its retained draft. Fix the failed checks and validate before saving.\n\nOriginal request:\n"+original.getContent();
        if(prompt.length()>32_000) throw new AgentRunConflictException("Original request is too long for automatic retry. Start a new build with your instructions.");
        turns.post(user,project,new CreateMessageRequest(request.id(),prompt,ProjectType.BUILD,request.aiMessageId(),original.attachmentPaths()));
        // The usual listener creates at BEFORE_COMMIT; create now so the seed binding is persisted first.
        // Its later idempotent call returns this same accepted request/run/outbox.
        var retry=creator.create(new AssistantTurnRequested(project,user,ProjectType.BUILD,request.aiMessageId()));
        if(retry.getRetrySourceRunId()!=null && !sourceId.equals(retry.getRetrySourceRunId())) throw new AgentRunConflictException("Retry identity conflict");
        runs.flush();
        if(runs.bindRetrySource(retry.getId(),project,sourceId)!=1) throw new AgentRunConflictException("Retry run is no longer available for acceptance");
        retry.setRetrySourceRunId(sourceId);repository.markRetry(project,sourceId,retry.getId());
        return retry;
    }
}
