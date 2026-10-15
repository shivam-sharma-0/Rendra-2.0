package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resolves durable request identity; never substitutes the project's latest user turn. */
record RunRequestHistory(Message request, List<Message> messages) {
    private static final Comparator<Message> ORDER = Comparator.comparing(Message::getCreatedAt)
            .thenComparingLong(Message::getSeq);

    static RunRequestHistory load(MessageRepository repository, AgentRun run, int limit) {
        Message assistant = repository.findByIdAndProjectId(run.getMessageId(), run.getProjectId())
                .orElseThrow(() -> invalid("Assistant placeholder is missing"));
        if (assistant.getRole() != MessageRole.ASSISTANT || assistant.getReplyToId() == null
                || !run.getProjectId().equals(assistant.getProjectId()))
            throw invalid("Assistant has no valid original request");
        Message request = repository.findByIdAndProjectId(assistant.getReplyToId(), run.getProjectId())
                .orElseThrow(() -> invalid("Original request is missing"));
        if (request.getRole() != MessageRole.USER || request.getCreatedAt() == null
                || !run.getProjectId().equals(request.getProjectId())
                || !Objects.equals(request.getAuthorId(), run.getUserId()))
            throw invalid("Original request does not belong to this run");
        if (run.getRequestMessageId() != null && (!run.getRequestMessageId().equals(request.getId())
                || !Objects.equals(run.getRequestFingerprint(), RequestIdentity.fingerprint(request))))
            throw invalid("Accepted request was modified");
        var page = repository.findHistoryThrough(run.getProjectId(), request.getCreatedAt(), request.getSeq(),
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("seq"))));
        List<Message> history = new ArrayList<>(page.getContent());
        history.removeIf(message -> !run.getProjectId().equals(message.getProjectId())
                || message.getCreatedAt() == null || ORDER.compare(message, request) > 0
                || message.getId().equals(request.getId())
                || message.getContent() == null || message.getContent().isBlank());
        history.sort(ORDER);
        // The canonical request is explicit, even for an image-only turn or empty history page.
        history.add(request);
        return new RunRequestHistory(request, List.copyOf(history));
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Cannot execute accepted request: " + reason);
    }
}
