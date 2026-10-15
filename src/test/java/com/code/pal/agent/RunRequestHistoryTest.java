package com.code.pal.agent;

import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.testsupport.QueuedRequestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunRequestHistoryTest {
    @Test void bindsQueuedTextAndImagesToAAndBoundsBeforePaging() {
        var f = QueuedRequestFixture.create(ProjectType.BUILD);
        var repo = mock(MessageRepository.class);
        QueuedRequestFixture.stubOriginal(repo, f.runA(), f.requestA());
        // Deliberately inject later rows too: defensive filtering must retain canonical A.
        when(repo.findHistoryThrough(eq(f.runA().getProjectId()), any(), anyLong(), any()))
                .thenReturn(new PageImpl<>(f.newestFirst()));
        var accepted = RunRequestHistory.load(repo, f.runA(), 100);
        assertEquals("Build a gallery", accepted.request().getContent());
        assertEquals(List.of("uploads/a.png"), accepted.request().attachmentPaths());
        assertEquals(List.of(f.requestA()), accepted.messages());
        verify(repo).findHistoryThrough(eq(f.runA().getProjectId()), eq(f.requestA().getCreatedAt()),
                eq(f.requestA().getSeq()), argThat((Pageable p) -> p.getPageSize() == 100 && p.getPageNumber() == 0));
        verify(repo, never()).findByProjectId(any(), any());
    }

    @Test void missingOriginNeverFallsBackToLatestRequest() {
        var f = QueuedRequestFixture.create(ProjectType.CHAT);
        var repo = mock(MessageRepository.class);
        when(repo.findByIdAndProjectId(f.assistantA().getId(), f.runA().getProjectId()))
                .thenReturn(Optional.of(f.assistantA()));
        assertThrows(IllegalStateException.class, () -> RunRequestHistory.load(repo, f.runA(), 100));
        verify(repo, never()).findHistoryThrough(any(), any(), anyLong(), any());
    }

    @Test void refusesAnotherActorOrProject() {
        var f = QueuedRequestFixture.create(ProjectType.BUILD);
        var repo = mock(MessageRepository.class);
        QueuedRequestFixture.stubOriginal(repo, f.runA(), f.requestA());
        f.requestA().setAuthorId(8L);
        assertThrows(IllegalStateException.class, () -> RunRequestHistory.load(repo, f.runA(), 100));
        f.requestA().setAuthorId(7L);
        f.requestA().setProjectId(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> RunRequestHistory.load(repo, f.runA(), 100));
    }

    @Test void keepsEarlierConversationAndImageOnlyOrigin() {
        var f = QueuedRequestFixture.create(ProjectType.CHAT);
        var repo = mock(MessageRepository.class);
        QueuedRequestFixture.stubOriginal(repo, f.runA(), f.requestA());
        var earlier = com.code.pal.message.domain.Message.user(f.runA().getProjectId(), "earlier", "Previous decision", ProjectType.CHAT, 8L);
        earlier.setCreatedAt(f.requestA().getCreatedAt().minusSeconds(1));
        f.requestA().setContent("");
        when(repo.findHistoryThrough(eq(f.runA().getProjectId()), any(), anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(f.requestA(), earlier)));
        assertEquals(List.of(earlier, f.requestA()), RunRequestHistory.load(repo, f.runA(), 100).messages());
    }
}
