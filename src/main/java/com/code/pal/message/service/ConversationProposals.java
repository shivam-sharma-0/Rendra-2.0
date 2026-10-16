package com.code.pal.message.service;

import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.exception.MessageConflictException;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.ProjectType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Binds explicit acceptance inside the message transaction; locks and consumption commit together. */
@Service
@RequiredArgsConstructor
public class ConversationProposals {
    private static final int MAX_PROPOSAL_CONTEXT = 2_000;
    private final MessageRepository messages;

    public record Resolution(String prompt, List<String> attachments, Message proposal) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Resolution resolve(Long userId, UUID projectId, CreateMessageRequest request, ProjectType mode) {
        List<String> attachments = request.attachments() == null ? List.of() : request.attachments();
        boolean explicit = request.proposalId() != null;
        boolean continuation = RequestRouter.route(request.message(), attachments, true) == RequestRouter.Route.CONTINUE;
        if (!explicit && !continuation) {
            return new Resolution(request.message(), attachments, null);
        }
        var latest = messages.lockLatestProposal(projectId, userId, PageRequest.of(0, 1));
        if (latest.isEmpty() || latest.getFirst().getProposalConsumedBy() != null) {
            if (explicit) {
                throw new MessageConflictException();
            }
            return new Resolution(request.message(), attachments, null);
        }
        Message proposal = latest.getFirst();
        if (explicit && (mode != ProjectType.BUILD || !proposal.getId().equals(request.proposalId())
                || !proposal.getSuggestedBuildText().equals(request.message()))) {
            throw new MessageConflictException();
        }
        if ((explicit || !attachments.isEmpty()) && !attachments.equals(proposal.attachmentPaths())) {
            if (explicit) {
                throw new MessageConflictException();
            }
            return new Resolution(request.message(), attachments, null);
        }
        String prompt = executionPrompt(proposal);
        if (mode == ProjectType.BUILD && request.aiMessageId() != null && !request.aiMessageId().isBlank()) {
            proposal.setProposalConsumedBy(request.id());
            messages.save(proposal);
        }
        return new Resolution(prompt, proposal.attachmentPaths(), proposal);
    }

    /** A newer explicit task supersedes a previous offer, even when it takes another route. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Message> retirePrevious(UUID projectId, String requestId) {
        var open = messages.lockOpenProposals(projectId);
        for (Message proposal : open) {
            proposal.setProposalConsumedBy(requestId);
        }
        return messages.saveAll(open);
    }

    /** Only server-owned, persisted proposal fields are used to resolve an accepted request. */
    public static String executionPrompt(Message proposal) {
        String prompt = "Build the proposed changes.\n\nOriginal request:\n" + quote(proposal.getSuggestedBuildText());
        if (proposal.getProposalPlanId() != null) {
            prompt += "\n\nImplementation proposal " + proposal.getProposalPlanId() + ":\n" + quote(proposal.getContent());
        } else if (proposal.getContent() != null && !proposal.getContent().isBlank()
                && !proposal.getContent().strip().equals(proposal.getSuggestedBuildText().strip())) {
            // The user accepted the offer as it was shown to them, so its wording is context for the build; their own
            // request stays the instruction. Approved storage/auth decisions are still enforced by the server.
            prompt += "\n\nThe assistant proposed this and the user accepted it. Use it for detail; "
                    + "the original request wins on any conflict:\n" + quote(bounded(proposal.getContent()));
        }
        return prompt;
    }

    private static String bounded(String text) {
        String clean = text.strip();
        return clean.length() <= MAX_PROPOSAL_CONTEXT ? clean : clean.substring(0, MAX_PROPOSAL_CONTEXT).strip();
    }

    private static String quote(String text) {
        return text.lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
    }
}
