package com.code.pal.agent;

import com.code.pal.agent.dispatch.*;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.exception.MessageConflictException;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.message.service.ConversationProposals;
import com.code.pal.message.service.impl.MessageServiceImpl;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.exception.RequirementApprovalRequiredException;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtomicAgentAcceptanceIT {
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private UUID projectId;
    private MessageService messages;
    private AgentRunRepository runs;
    private AgentDispatchRepository outbox;
    private AgentJobQueue queue;
    private JdbcTemplate jdbc;

    @BeforeAll void initialize() { context = CodexJpaFixture.open(Services.class); }
    @AfterAll void close() { if (context != null) context.close(); }
    @BeforeEach void scenario() {
        projectId = UUID.randomUUID();
        messages = context.getBean(MessageService.class);
        runs = context.getBean(AgentRunRepository.class);
        outbox = context.getBean(AgentDispatchRepository.class);
        queue = context.getBean(AgentJobQueue.class);
        jdbc = context.getBean(JdbcTemplate.class);
        reset(queue);
        when(context.getBean(ProjectService.class).requireCanEdit(eq(7L), eq(projectId))).thenReturn(
                Project.builder().id(projectId).ownerId(7L).workspaceId(UUID.randomUUID()).type(ProjectType.CHAT).build());
    }

    private CreateMessageRequest request(String suffix) {
        return new CreateMessageRequest("u_" + suffix, "Explain existing code", ProjectType.CHAT, "a_" + suffix);
    }
    @Test void explicitReadOnlyScopePersistsEffectiveModeAndOriginalModeForIdempotency() {
        when(context.getBean(ProjectService.class).requireCanEdit(eq(7L),eq(projectId))).thenReturn(
                Project.builder().id(projectId).ownerId(7L).workspaceId(UUID.randomUUID()).type(ProjectType.BUILD)
                        .lifecycleStage(com.code.pal.project.domain.ProjectStage.REVIEW).build());
        var request=new CreateMessageRequest("u_readonly","Dont change anything, explain this API",ProjectType.BUILD,"a_readonly");
        messages.post(7L,projectId,request);messages.post(7L,projectId,request);
        var run=runs.findByProjectIdAndRequestMessageId(projectId,"u_readonly").orElseThrow();
        assertEquals(ProjectType.CHAT,run.getMode());
        var saved=context.getBean(MessageRepository.class).findByIdAndProjectId("u_readonly",projectId).orElseThrow();
        assertEquals(ProjectType.CHAT,saved.getMode());assertEquals(ProjectType.BUILD,saved.getRequestedMode());
        assertEquals(1,runs.countByProjectId(projectId));
    }
    private AgentRun accepted(String suffix) {
        messages.post(7L, projectId, request(suffix));
        return runs.findByProjectIdAndRequestMessageId(projectId, "u_" + suffix).orElseThrow();
    }

    @Test void concurrentStopAndSaveHaveExactlyOneDurableWinner() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for(int round=0;round<12;round++) {
                var run=accepted(UUID.randomUUID().toString().substring(0,12));
                run.setStatus(com.code.pal.agent.domain.RunStatus.RUNNING);runs.save(run);
                var start=new java.util.concurrent.CountDownLatch(1);
                var stop=pool.submit(()->{start.await();return runs.recordCancellation(run.getId(),projectId,8L,Instant.now());});
                var save=pool.submit(()->{start.await();return runs.beginCommit(run.getId(),projectId,Instant.now());});
                start.countDown();int cancelled=stop.get(5,java.util.concurrent.TimeUnit.SECONDS),committed=save.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1,cancelled+committed);
                var durable=runs.findById(run.getId()).orElseThrow();
                if(cancelled==1) {
                    assertNotNull(durable.getCancelRequestedAt());assertNull(durable.getCommitStartedAt());
                    assertEquals(0,runs.beginCommit(run.getId(),projectId,Instant.now()));
                } else {
                    assertEquals(com.code.pal.agent.domain.RunStatus.COMMITTING,durable.getStatus());
                    assertNull(durable.getCancelRequestedAt());assertNotNull(durable.getCommitStartedAt());
                    assertEquals(0,runs.recordCancellation(run.getId(),projectId,8L,Instant.now()));
                }
            }
        }
    }
    @Test void staleSavesCannotEraseCodeSavedAndPreviewTruth() {
        var run=accepted(UUID.randomUUID().toString().substring(0,12));run.setStatus(com.code.pal.agent.domain.RunStatus.RUNNING);runs.save(run);
        assertEquals(1,runs.beginCommit(run.getId(),projectId,Instant.now()));
        var stale=runs.findById(run.getId()).orElseThrow();
        assertEquals(1,runs.recordCodeSaved(run.getId(),Instant.now()));assertEquals(1,runs.recordPreviewStatus(run.getId(),"FAILED"));
        stale.setError("Stale display write");runs.save(stale);
        var durable=runs.findById(run.getId()).orElseThrow();assertNotNull(durable.getCodeSavedAt());assertEquals("FAILED",durable.getPreviewStatus());
    }

    @Test void acceptsMessagesRunAndOutboxTogetherAndRetriesWithoutANewRun() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        AgentRun run = accepted(key);
        assertNotNull(run.getRequestFingerprint());
        assertEquals(2, messages.post(7L, projectId, request(key)).size());
        assertEquals(1, runs.countByProjectId(projectId));
        assertNotNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        verify(queue, times(1)).enqueue(run.getId());
        context.getBean(AgentDispatchService.class).dispatch(run.getId());
        verify(queue, times(1)).enqueue(run.getId());
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest("u_" + key, "Different request", ProjectType.CHAT, "a_" + key)));
    }

    @Test void queueOutageLeavesReplayableCommittedIntent() {
        doThrow(new IllegalStateException("Scripted queue outage")).when(queue).enqueue(any());
        String key = UUID.randomUUID().toString().substring(0, 12);
        var run = accepted(key);
        assertEquals(2, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        reset(queue);
        context.getBean(AgentDispatchPoller.class).poll();
        assertNotNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        verify(queue).enqueue(run.getId());
    }

    @Test void failureBeforeCommitLeavesNoAcceptedMessageRunOrDelivery() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            messages.post(7L, projectId, request(key));
            // A second malformed delivery event fails BEFORE_COMMIT after the valid intent is created.
            context.publishEvent(new AssistantTurnRequested(projectId, 7L, ProjectType.CHAT, "missing"));
        }));
        assertEquals(0, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertEquals(0, runs.countByProjectId(projectId));
        verifyNoInteractions(queue);
    }

    @Test void originalHistoryIsBoundedInPostgresAndStaleSavesPreserveCancelAudit() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var run = accepted(key);
        var repo = context.getBean(MessageRepository.class);
        var origin = repo.findById(run.getRequestMessageId()).orElseThrow();
        Message later = Message.user(projectId, "later_" + key, "Do something else", ProjectType.CHAT, 8L);
        // SQL enforces an exact same-clock tie; later seq must be excluded before pagination.
        jdbc.update("insert into messages(id,project_id,author_id,role,status,mode,content,created_at,updated_at,seq,attachments) values (?,?,8,'USER','COMPLETE','CHAT',?,?,?, ?,?)",
                later.getId(), projectId, later.getContent(), java.sql.Timestamp.from(origin.getCreatedAt()),
                java.sql.Timestamp.from(origin.getCreatedAt()), origin.getSeq() + 10, "uploads/b.png");
        var history = RunRequestHistory.load(repo, run, 1);
        assertEquals(origin.getId(), history.messages().getLast().getId());
        assertEquals(1, history.messages().size());
        assertEquals(1, runs.recordCancellation(run.getId(), projectId, 8L, Instant.now()));
        assertEquals(0, runs.recordCancellation(run.getId(), projectId, 9L, Instant.now()));
        run.setError("Stale runner save");
        runs.saveAndFlush(run);
        assertEquals(8L, runs.findById(run.getId()).orElseThrow().getCancelRequestedBy());
    }

    @Test void concurrentOutboxReplayEnqueuesOnce() throws Exception {
        doThrow(new IllegalStateException("Scripted queue outage")).when(queue).enqueue(any());
        var run = accepted(UUID.randomUUID().toString().substring(0, 12));
        reset(queue);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> replay = () -> {
                assertTrue(start.await(3, java.util.concurrent.TimeUnit.SECONDS));
                context.getBean(AgentDispatchService.class).dispatch(run.getId());
                return null;
            };
            var first = pool.submit(replay);
            var second = pool.submit(replay);
            start.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(queue, times(1)).enqueue(run.getId());
    }

    @Test void modifiedAcceptedPromptFailsInsteadOfExecutingTheNewText() {
        var run = accepted(UUID.randomUUID().toString().substring(0, 12));
        jdbc.update("update messages set content = 'modified after acceptance' where id = ?", run.getRequestMessageId());
        assertThrows(IllegalStateException.class, () -> RunRequestHistory.load(context.getBean(MessageRepository.class), run, 100));
    }

    @Test void additiveMigrationPreservesLegacyDuplicateRuns() {
        assertEquals(2, jdbc.queryForObject("select count(*) from agent_runs where message_id = 'legacy_duplicate'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from agent_runs where message_id = 'legacy_duplicate' and request_message_id is not null", Integer.class));
    }

    @Test void concurrentDuplicateSubmitHasOneDurableRunAndCanRetry() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> submit = () -> {
                assertTrue(start.await(3, java.util.concurrent.TimeUnit.SECONDS));
                try { messages.post(7L, projectId, request(key)); return true; }
                catch (org.springframework.dao.DataIntegrityViolationException concurrentInsert) { return false; }
            };
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            start.countDown();
            boolean a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a || b);
        }
        assertEquals(1, runs.countByProjectId(projectId));
        assertEquals(2, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertEquals(2, messages.post(7L, projectId, request(key)).size());
        var run = runs.findByProjectIdAndRequestMessageId(projectId, "u_" + key).orElseThrow();
        verify(queue, times(1)).enqueue(run.getId());
    }

    @Test void retriesKeepCanonicalImagesAndRejectDifferentModeOrAssistant() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        when(context.getBean(ProjectFiles.class).regularFile(projectId, ".", "uploads/a.png")).thenReturn(true);
        var request = new CreateMessageRequest("u_" + key, "Read image", ProjectType.CHAT, "a_" + key,
                java.util.List.of("uploads/a.png", "uploads/a.png"));
        messages.post(7L, projectId, request);
        when(context.getBean(ProjectFiles.class).regularFile(projectId, ".", "uploads/a.png")).thenReturn(false);
        assertEquals(2, messages.post(7L, projectId, request).size());
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest(request.id(), request.message(), ProjectType.BUILD, request.aiMessageId(), request.attachments())));
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest(request.id(), request.message(), ProjectType.CHAT, "different", request.attachments())));
    }

    private void pendingOffer(String suffix, String prompt) {
        when(context.getBean(ProjectService.class).requireCanEdit(7L, projectId)).thenReturn(
                Project.builder().id(projectId).ownerId(7L).type(ProjectType.BUILD)
                        .lifecycleStage(ProjectStage.REVIEW).build());
        messages.post(7L, projectId, new CreateMessageRequest("u_" + suffix, prompt, ProjectType.CHAT, "a_" + suffix));
        jdbc.update("update messages set status='COMPLETE',content='Proposed change',suggested_build_text=? where id=?",
                prompt, "a_" + suffix);
        jdbc.update("update agent_runs set status='DONE' where project_id=? and message_id=?", projectId, "a_" + suffix);
    }

    @Test
    void continuationConsumesTheLastProposalOnceAndRetriesTheAcceptedTurn() {
        pendingOffer("proposal", "add dark mode");
        var yes = new CreateMessageRequest("u_yes", "haan", ProjectType.BUILD, "a_yes");
        var accepted = messages.post(7L, projectId, yes);
        assertEquals(ProjectType.BUILD, accepted.getFirst().mode());
        assertEquals("haan", accepted.getFirst().content());
        assertEquals(accepted.getFirst().id(), messages.post(7L, projectId, yes).getFirst().id());
        assertEquals(ProjectType.CHAT, messages.post(7L, projectId,
                new CreateMessageRequest("u_again", "yes", ProjectType.BUILD, "a_again")).getFirst().mode());
    }

    @Test
    void continuationNeverResurrectsAnOlderOfferAfterTheLatestIsConsumed() {
        pendingOffer("old_offer", "add dark mode");
        pendingOffer("latest_offer", "add a timer");
        assertEquals(ProjectType.BUILD, messages.post(7L, projectId,
                new CreateMessageRequest("u_latest", "go ahead", ProjectType.BUILD, "a_latest")).getFirst().mode());
        assertEquals(ProjectType.CHAT, messages.post(7L, projectId,
                new CreateMessageRequest("u_old", "haan", ProjectType.BUILD, "a_old")).getFirst().mode());
    }

    @Test
    void explicitBuildActionRequiresTheLatestMatchingOwnedOfferAndCannotReplay() {
        pendingOffer("first_click", "add dark mode");
        pendingOffer("current_click", "add a timer");
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest("u_stale", "add dark mode", ProjectType.BUILD, "a_stale", List.of(), "a_first_click")));
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest("u_mismatch", "delete everything", ProjectType.BUILD, "a_mismatch", List.of(), "a_current_click")));
        var click = new CreateMessageRequest("u_click", "add a timer", ProjectType.BUILD, "a_click", List.of(), "a_current_click");
        assertEquals(ProjectType.BUILD, messages.post(7L, projectId, click).getFirst().mode());
        assertEquals("a_current_click", jdbc.queryForObject("select source_proposal_id from messages where id='u_click'", String.class));
        assertTrue(jdbc.queryForObject("select resolved_request_text from messages where id='u_click'", String.class).contains("add a timer"));
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest("u_replay", "add a timer", ProjectType.BUILD, "a_replay", List.of(), "a_current_click")));
    }

    @Test
    void chatAcknowledgementPreservesModeAndDoesNotConsumeTheBuildOffer() {
        pendingOffer("chat_offer", "add dark mode");
        assertEquals(ProjectType.CHAT, messages.post(7L, projectId,
                new CreateMessageRequest("u_chat_yes", "haan", ProjectType.CHAT, "a_chat_yes")).getFirst().mode());
        assertNull(jdbc.queryForObject("select proposal_consumed_by from messages where id='a_chat_offer'", String.class));
        assertEquals(ProjectType.BUILD, messages.post(7L, projectId,
                new CreateMessageRequest("u_build_yes", "haan", ProjectType.BUILD, "a_build_yes")).getFirst().mode());
    }

    @Test
    void rejectedAdmissionRollsBackProposalConsumption() {
        pendingOffer("quota_offer", "add dark mode");
        var admission = context.getBean(AgentTurnAdmission.class);
        doThrow(new IllegalStateException("Fixture quota refusal")).when(admission).requireAdmitted(7L);
        try {
            assertThrows(IllegalStateException.class, () -> messages.post(7L, projectId,
                    new CreateMessageRequest("u_refused", "yes", ProjectType.BUILD, "a_refused")));
            assertNull(jdbc.queryForObject("select proposal_consumed_by from messages where id='a_quota_offer'", String.class));
        } finally {
            reset(admission);
        }
    }

    @Test
    void pendingRequirementsCannotBeBypassedByAcceptingAnOffer() {
        pendingOffer("requirements_offer", "add dark mode");
        when(context.getBean(ProjectService.class).requireCanEdit(7L, projectId)).thenReturn(
                Project.builder().id(projectId).ownerId(7L).type(ProjectType.BUILD)
                        .lifecycleStage(ProjectStage.REQUIREMENTS).build());
        assertThrows(RequirementApprovalRequiredException.class,
                () -> messages.post(7L, projectId, new CreateMessageRequest("u_need_req", "yes", ProjectType.BUILD, "a_need_req")));
        assertNull(jdbc.queryForObject("select proposal_consumed_by from messages where id='a_requirements_offer'", String.class));
    }

    @Test
    void concurrentAcknowledgementsCanConsumeAnOfferOnlyOnce() throws Exception {
        pendingOffer("race_offer", "add dark mode");
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return messages.post(7L, projectId, new CreateMessageRequest("u_race_1", "yes", ProjectType.BUILD, "a_race_1")).getFirst().mode();
            });
            var second = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return messages.post(7L, projectId, new CreateMessageRequest("u_race_2", "yes", ProjectType.BUILD, "a_race_2")).getFirst().mode();
            });
            var modes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, modes.stream().filter(mode -> mode == ProjectType.BUILD).count());
            assertEquals(1, modes.stream().filter(mode -> mode == ProjectType.CHAT).count());
        }
    }

    @Test
    void anotherExplicitTaskSupersedesThePreviousOffer() {
        pendingOffer("superseded", "add dark mode");
        messages.post(7L, projectId, new CreateMessageRequest("u_independent", "add a timer", ProjectType.BUILD, "a_independent"));
        assertEquals("u_independent", jdbc.queryForObject("select proposal_consumed_by from messages where id='a_superseded'", String.class));
        assertEquals(ProjectType.CHAT, messages.post(7L, projectId,
                new CreateMessageRequest("u_after_task", "yes", ProjectType.BUILD, "a_after_task")).getFirst().mode());
    }

    @Test
    void anotherCollaboratorCannotAcceptOrContinueTheOwnersOffer() {
        pendingOffer("owner_offer", "add dark mode");
        when(context.getBean(ProjectService.class).requireCanEdit(8L, projectId)).thenReturn(
                Project.builder().id(projectId).ownerId(7L).type(ProjectType.BUILD).lifecycleStage(ProjectStage.REVIEW).build());
        assertThrows(MessageConflictException.class, () -> messages.post(8L, projectId,
                new CreateMessageRequest("u_foreign_click", "add dark mode", ProjectType.BUILD, "a_foreign_click", List.of(), "a_owner_offer")));
        assertEquals(ProjectType.CHAT, messages.post(8L, projectId,
                new CreateMessageRequest("u_foreign_yes", "yes", ProjectType.BUILD, "a_foreign_yes")).getFirst().mode());
        assertNull(jdbc.queryForObject("select proposal_consumed_by from messages where id='a_owner_offer'", String.class));
    }

    @Configuration
    static class Services {
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean RealtimeService realtime() { return mock(RealtimeService.class); }
        @Bean AgentJobQueue queue() { return mock(AgentJobQueue.class); }
        @Bean AgentEvents events() { return mock(AgentEvents.class); }
        @Bean AgentTurnAdmission admission() { return mock(AgentTurnAdmission.class); }
        @Bean ProjectFiles files() { return mock(ProjectFiles.class); }
        @Bean MessageSequence sequence() { return new MessageSequence(); }
        @Bean MessageServiceImpl messageService(MessageRepository m, ProjectService p, RealtimeService r,
                                               ApplicationEventPublisher e, MessageSequence s, AgentTurnAdmission a, ProjectFiles f) {
            return new MessageServiceImpl(m, p, r, e, s, a, f, new ConversationProposals(m));
        }
        @Bean AgentRunCreator creator(AgentRunRepository r, MessageRepository m, AgentDispatchRepository o) {
            return new AgentRunCreator(r, m, o);
        }
        @Bean AgentDispatchService dispatcher(AgentDispatchRepository o, AgentRunRepository r, AgentJobQueue q, AgentEvents e) {
            return new AgentDispatchService(o, r, q, e);
        }
        @Bean AgentTriggerListener listener(AgentRunCreator c, AgentRunRepository r, AgentDispatchService d) {
            return new AgentTriggerListener(c, r, d);
        }
        @Bean AgentDispatchPoller poller(AgentDispatchRepository o, AgentDispatchService d) { return new AgentDispatchPoller(o, d); }
    }
}
