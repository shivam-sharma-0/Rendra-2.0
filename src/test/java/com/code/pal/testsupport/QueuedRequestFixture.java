package com.code.pal.testsupport;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.message.domain.Message;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.message.repository.MessageRepository;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Includes a later request from a different collaborator and same-clock sequence ties. */
public record QueuedRequestFixture(AgentRun runA, Message requestA, Message assistantA,
                                   Message requestB, Message assistantB) {
    public static QueuedRequestFixture create(ProjectType mode) {
        UUID project = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-10T23:59:59Z");
        Message a = Message.user(project, "user-a", "Build a gallery", mode, 7L);
        a.setAttachments("uploads/a.png");
        a.setCreatedAt(time);
        a.setSeq(1);
        Message aa = Message.assistantPlaceholder(project, "assistant-a", a.getId(), mode);
        aa.setCreatedAt(time);
        aa.setSeq(2);
        Message b = Message.user(project, "user-b", "Build a calendar", mode, 8L);
        b.setAttachments("uploads/b.png");
        b.setCreatedAt(time);
        b.setSeq(3);
        Message ab = Message.assistantPlaceholder(project, "assistant-b", b.getId(), mode);
        ab.setCreatedAt(time);
        ab.setSeq(4);
        AgentRun run = AgentRun.builder().id(UUID.randomUUID()).projectId(project).userId(7L)
                .mode(mode).messageId(aa.getId()).status(RunStatus.QUEUED).build();
        return new QueuedRequestFixture(run, a, aa, b, ab);
    }

    public List<Message> newestFirst() { return List.of(assistantB, requestB, assistantA, requestA); }

    /** Existing runner harnesses must model the same persisted identity as accepted messages. */
    public static void stubOriginal(MessageRepository repository, AgentRun run, Message request) {
        if (request.getCreatedAt() == null) request.setCreatedAt(Instant.parse("2026-10-10T12:00:00Z"));
        Message assistant = Message.assistantPlaceholder(run.getProjectId(), run.getMessageId(), request.getId(), run.getMode());
        when(repository.findByIdAndProjectId(run.getMessageId(), run.getProjectId())).thenReturn(Optional.of(assistant));
        when(repository.findByIdAndProjectId(request.getId(), run.getProjectId())).thenReturn(Optional.of(request));
        when(repository.findHistoryThrough(eq(run.getProjectId()), eq(request.getCreatedAt()), eq(request.getSeq()), any()))
                .thenReturn(new PageImpl<>(List.of(request)));
    }
}
