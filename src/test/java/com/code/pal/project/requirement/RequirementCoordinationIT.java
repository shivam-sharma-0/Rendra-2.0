package com.code.pal.project.requirement;

import com.code.pal.message.service.ConversationProposals;

import com.code.pal.agent.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.dispatch.*;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.*;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.service.impl.MessageServiceImpl;
import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.*;
import com.code.pal.project.dto.RequirementDtos;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.*;
import com.code.pal.project.service.RequirementService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.project.service.impl.RequirementServiceImpl;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real DB transactions/outbox and native fenced files; finite fake model, no app scan/scheduler. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequirementCoordinationIT {
    AnnotationConfigApplicationContext context;
    RequirementService service;
    RequirementSessionRepository sessions;
    RequirementQuestionRepository questions;
    JdbcTemplate jdbc;
    UUID project;
    ProjectFiles files;
    ControlledModel model;
    @BeforeAll void open() { context = CodexJpaFixture.open(Services.class); }
    @AfterAll void close() { if (context != null) context.close(); }
    @BeforeEach void scenario() {
        project = UUID.randomUUID();
        service = context.getBean(RequirementService.class);
        sessions = context.getBean(RequirementSessionRepository.class);
        questions = context.getBean(RequirementQuestionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        files = context.getBean(ProjectFiles.class);
        model = context.getBean(ControlledModel.class); model.reset();
        reset(context.getBean(AgentJobQueue.class), context.getBean(AgentTurnAdmission.class));
        clearInvocations(context.getBean(HostProjectFiles.class));
        doCallRealMethod().when(context.getBean(HostProjectFiles.class)).writeText(any(), anyString(), anyString(), anyString());
        Project product = Project.builder().id(project).workspaceId(UUID.randomUUID()).ownerId(7L).type(ProjectType.BUILD)
                .name("Fixture app").initialPrompt("Create a task app").lifecycleStage(ProjectStage.REQUIREMENTS).build();
        var access = context.getBean(ProjectService.class);
        when(access.requireCanEdit(7L, project)).thenReturn(product);
        when(access.requireAccessible(7L, project)).thenReturn(product);
        when(access.advanceLifecycle(eq(7L), eq(project), any())).thenAnswer(call -> { product.setLifecycleStage(call.getArgument(2)); return null; });
    }

    @Test void concurrentStartCreatesOneSessionAndSendsOneQuestionCall() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        model.block(entered, release, "[]");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.start(7L, project));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.start(7L, project)).get(3, TimeUnit.SECONDS);
            assertTrue(second.processing()); assertFalse(second.complete());
            assertEquals(1, model.calls.get());
            release.countDown();
            var completed = first.get(3, TimeUnit.SECONDS);
            assertFalse(completed.processing()); assertTrue(completed.complete());
            assertEquals(RequirementStatus.APPROVAL_REQUIRED, completed.status());
            assertEquals(2, completed.questions().size(), "a clear request asks nothing beyond the preselected platform choices");
            assertEquals(1, jdbc.queryForObject("select count(*) from requirement_sessions where project_id=?", Integer.class, project));
            assertEquals(completed.id(), service.start(7L, project).id());
            assertEquals(1, model.calls.get());
        } finally { release.countDown(); }
    }

    @Test void lastAnswerGoesStraightToApprovalWithoutAModelCall() {
        seed(RequirementStatus.IN_PROGRESS);
        var auth = question(BuildDecisions.AUTH_KEY); auth.setSelectedOption(null); questions.saveAndFlush(auth);
        var reviewed = service.answer(7L, project, auth.getId(), new RequirementDtos.AnswerRequest("NONE", null, 2L));
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, reviewed.status());
        assertFalse(reviewed.processing());
        service.answer(7L, project, auth.getId(), new RequirementDtos.AnswerRequest("NONE", null, reviewed.revision()));
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, service.get(7L, project).status());
        assertEquals(0, model.calls.get());
    }

    @Test void completeSessionLeftInProgressReachesApprovalOnStartWithoutAModelCall() {
        seed(RequirementStatus.IN_PROGRESS);
        var recovered = service.start(7L, project);
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, recovered.status()); assertFalse(recovered.processing());
        assertEquals(0, model.calls.get());
    }

    @Test void expiredQuestionGenerationUsesDefaultsAndKeepsTheOneCallRecord() {
        var session = sessions.saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("fixture").answerRevision(2).build());
        expired(session, "QUESTIONS", UUID.randomUUID());
        var recovered = service.start(7L, project);
        assertFalse(recovered.processing()); assertFalse(recovered.complete()); assertEquals(0, model.calls.get());
        assertTrue(recovered.questions().stream().anyMatch(q -> q.key().equals(BuildDecisions.STORAGE_KEY)));
        assertEquals(1, jdbc.queryForObject("select count(*) from requirement_llm_work where session_id=?", Integer.class, session.getId()));
    }

    @Test void staleAnswerAndApprovalRevisionsAreRejectedBeforeAnyWriteOrDispatch() {
        seed(RequirementStatus.APPROVAL_REQUIRED);
        var auth = question(BuildDecisions.AUTH_KEY);
        assertThrows(WorkflowStageException.class, () -> service.answer(7L, project, auth.getId(), new RequirementDtos.AnswerRequest("ACCOUNTS", null, 1L)));
        assertEquals("NONE", question(BuildDecisions.AUTH_KEY).getSelectedOption());
        assertThrows(WorkflowStageException.class, () -> service.approve(7L, project, 1L));
        assertEquals(0, projections()); assertEquals(0, context.getBean(AgentRunRepository.class).countByProjectId(project));
        assertFalse(files.exists(project, ".", "RENDRA.md"));
    }

    @Test void concurrentApprovalAcceptsOneBuildAndOneProjection() throws Exception {
        seed(RequirementStatus.APPROVAL_REQUIRED);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<RequirementDtos.Session> approve = () -> { start.await(); return service.approve(7L, project, 2L); };
            var first = pool.submit(approve); var second = pool.submit(approve); start.countDown();
            assertEquals(RequirementStatus.APPROVED, first.get(5, TimeUnit.SECONDS).status());
            assertEquals(RequirementStatus.APPROVED, second.get(5, TimeUnit.SECONDS).status());
        }
        assertEquals(1, projections()); assertEquals(1, context.getBean(AgentRunRepository.class).countByProjectId(project));
        context.getBean(AgentDispatchPoller.class).poll();
        var run = context.getBean(AgentRunRepository.class).findTop100ByProjectIdOrderByCreatedAtDesc(project).getFirst();
        verify(context.getBean(AgentJobQueue.class), times(1)).enqueue(run.getId());
        assertEquals("READY", service.get(7L, project).artifactStatus());
    }

    @Test void nthFileWriteFailureKeepsDurableApprovalAndBlocksQueueUntilVerifiedRetry() {
        seed(RequirementStatus.APPROVAL_REQUIRED);
        files.writeText(project, ".", "RENDRA.md", "# User notes\nKeep the logo.\n");
        var host = context.getBean(HostProjectFiles.class); var failures = new AtomicInteger();
        doAnswer(call -> {
            if (project.equals(call.getArgument(0)) && "RENDRA.md".equals(call.getArgument(2))) {
                try (var connection = context.getBean(javax.sql.DataSource.class).getConnection(); var statement = connection.prepareStatement("select status from requirement_sessions where project_id=?")) {
                    statement.setObject(1, project); try (var result = statement.executeQuery()) { assertTrue(result.next()); assertEquals("APPROVED", result.getString(1)); }
                }
                if (failures.getAndIncrement() == 0) throw new IllegalStateException("Nth-write fixture");
            }
            return call.callRealMethod();
        }).when(host).writeText(any(), anyString(), anyString(), anyString());
        var approved = service.approve(7L, project, 2L);
        assertEquals(RequirementStatus.APPROVED, approved.status()); assertEquals("PENDING", approved.artifactStatus());
        assertEquals(1, context.getBean(AgentRunRepository.class).countByProjectId(project));
        assertTrue(context.getBean(BuildDecisions.class).state(project).blocked());
        verifyNoInteractions(context.getBean(AgentJobQueue.class));
        context.getBean(RequirementProjection.class).tryProject(sessions.findByProjectId(project).orElseThrow().getProjectionId());
        assertEquals("READY", service.get(7L, project).artifactStatus());
        assertTrue(files.readText(project, ".", "RENDRA.md").contains("Keep the logo."));
        context.getBean(AgentDispatchPoller.class).poll();
        var run = context.getBean(AgentRunRepository.class).findTop100ByProjectIdOrderByCreatedAtDesc(project).getFirst();
        verify(context.getBean(AgentJobQueue.class), times(1)).enqueue(run.getId());
        service.approve(7L, project, 2L); assertEquals(1, projections());
    }

    @Test void admissionFailureRollsBackApprovalProjectionAndMessageIntentWithoutFiles() {
        var session = seed(RequirementStatus.APPROVAL_REQUIRED);
        doThrow(new IllegalStateException("admission fixture")).when(context.getBean(AgentTurnAdmission.class)).requireAdmitted(7L);
        assertThrows(IllegalStateException.class, () -> service.approve(7L, project, 2L));
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, sessions.findById(session.getId()).orElseThrow().getStatus());
        assertEquals(0, projections()); assertEquals(0, context.getBean(AgentRunRepository.class).countByProjectId(project));
        assertFalse(files.exists(project, ".", "RENDRA.md") || files.exists(project, ".codepal", "requirements.json"));
    }

    @Test void activeBuildPreventsChangingItsApprovedRequirements() {
        seed(RequirementStatus.APPROVAL_REQUIRED); service.approve(7L, project, 2L);
        var auth = question(BuildDecisions.AUTH_KEY);
        assertThrows(WorkflowStageException.class, () -> service.answer(7L, project, auth.getId(), new RequirementDtos.AnswerRequest("ACCOUNTS", null, 2L)));
        assertEquals(2, service.get(7L, project).revision()); assertEquals("NONE", question(BuildDecisions.AUTH_KEY).getSelectedOption());
    }

    @Test void incompatibleCapabilitiesCannotBeApprovedWithoutAnyModelReview() {
        seed(RequirementStatus.IN_PROGRESS);
        var reviewed = service.answer(7L, project, question(BuildDecisions.AUTH_KEY).getId(), new RequirementDtos.AnswerRequest("ACCOUNTS", null, 2L));
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, reviewed.status());
        assertThrows(WorkflowStageException.class, () -> service.approve(7L, project, reviewed.revision()));
        assertEquals(0, projections()); assertEquals(0, model.calls.get());
    }

    @Test void failedDatabaseAcknowledgementAfterFileWritesKeepsTheBuildGated() {
        seed(RequirementStatus.APPROVAL_REQUIRED);
        doThrow(new IllegalStateException("First projection fixture")).when(context.getBean(HostProjectFiles.class)).writeText(eq(project), eq("."), eq("RENDRA.md"), anyString());
        service.approve(7L, project, 2L);
        doCallRealMethod().when(context.getBean(HostProjectFiles.class)).writeText(any(), anyString(), anyString(), anyString());
        UUID projection = sessions.findByProjectId(project).orElseThrow().getProjectionId();
        var broken = new JdbcTemplate(context.getBean(javax.sql.DataSource.class)) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("set state='READY'")) throw new org.springframework.dao.DataAccessResourceFailureException("Acknowledgement fixture");
                return super.update(sql, args);
            }
        };
        var projector = new RequirementProjection(broken, context.getBean(org.springframework.transaction.PlatformTransactionManager.class), files,
                context.getBean(ProjectLeaseService.class), context.getBean(RequirementCoordinator.class), context.getBean(ObjectMapper.class));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> projector.project(projection));
        assertEquals("PENDING", service.get(7L, project).artifactStatus());
        assertTrue(files.exists(project, ".", "RENDRA.md"));
        var lease = context.getBean(ProjectLeaseService.class);
        assertNull(lease.claim(project, UUID.randomUUID(), UUID.randomUUID()));
        verifyNoInteractions(context.getBean(AgentJobQueue.class));
        context.getBean(RequirementProjection.class).tryProject(projection);
        assertEquals("READY", service.get(7L, project).artifactStatus());
    }

    @Test void theSpecDraftedWithTheQuestionsIsApprovedWhenTheRecommendedAnswersAreKept() {
        model.answer("{\"questions\":[],\"spec\":{\"title\":\"Tasks\"}}");
        var started = service.start(7L, project);
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, started.status());
        service.approve(7L, project, started.revision());
        assertEquals("{\"title\":\"Tasks\"}", approvedSpecJson());
        assertEquals(1, model.calls.get(), "questions and spec come from one call");
    }

    @Test void aChangedAnswerLeavesTheStaleDraftSpecOutOfTheApproval() {
        model.answer("{\"questions\":[{\"key\":\"audience\",\"prompt\":\"Who?\",\"required\":true,\"recommended\":\"me\",\"options\":["
                + "{\"id\":\"me\",\"label\":\"Me\"},{\"id\":\"team\",\"label\":\"Team\"},{\"id\":\"other\",\"label\":\"Other\"}]}],"
                + "\"spec\":{\"title\":\"Tasks\"}}");
        var started = service.start(7L, project);
        var answered = service.answer(7L, project, question("audience").getId(), new RequirementDtos.AnswerRequest("team", null, started.revision()));
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, answered.status());
        service.approve(7L, project, answered.revision());
        assertNull(approvedSpecJson());
        assertEquals(1, model.calls.get());
    }

    private String approvedSpecJson() {
        String payload = jdbc.queryForObject("select payload_json from requirement_projections where project_id=?", String.class, project);
        return context.getBean(ObjectMapper.class).readValue(payload, RequirementProjection.Payload.class).specJson();
    }

    private RequirementSession seed(RequirementStatus status) {
        var session = sessions.saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("fixture").answerRevision(2).status(status).build());
        saveQuestion(session, BuildDecisions.STORAGE_KEY, "BROWSER", "[{\"id\":\"NONE\"},{\"id\":\"BROWSER\"},{\"id\":\"MERN\"}]", 1);
        saveQuestion(session, BuildDecisions.AUTH_KEY, "NONE", "[{\"id\":\"NONE\"},{\"id\":\"ACCOUNTS\"}]", 2);
        return session;
    }
    private void saveQuestion(RequirementSession session, String key, String answer, String options, int seq) {
        questions.saveAndFlush(RequirementQuestion.builder().sessionId(session.getId()).sequence(seq).questionKey(key).prompt(key)
                .type(RequirementQuestionType.SINGLE_SELECT).required(true).selectedOption(answer).optionsJson(options).build());
    }
    private RequirementQuestion question(String key) {
        return questions.findBySessionIdOrderBySequenceAsc(sessions.findByProjectId(project).orElseThrow().getId()).stream().filter(q -> q.getQuestionKey().equals(key)).findFirst().orElseThrow();
    }
    private void expired(RequirementSession session, String purpose, UUID owner) {
        jdbc.update("insert into requirement_llm_work(session_id,revision,purpose,owner_id,user_id,state,expires_at) values (?,?,?, ?,7,'RUNNING',clock_timestamp()-interval '1 second')", session.getId(), session.getAnswerRevision(), purpose, owner);
    }
    private int projections() { return jdbc.queryForObject("select count(*) from requirement_projections where project_id=?", Integer.class, project); }

    static class ControlledModel implements LlmClient {
        final Queue<Function<LlmRequest,LlmResult>> outcomes = new ConcurrentLinkedQueue<>();
        final AtomicInteger calls = new AtomicInteger();
        void reset() { outcomes.clear(); calls.set(0); }
        void answer(String text) { outcomes.add(request -> result(text)); }
        void fail() { outcomes.add(request -> { throw new IllegalStateException("Provider fixture"); }); }
        void block(CountDownLatch entered, CountDownLatch release, String text) {
            outcomes.add(request -> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException stopped) { throw new RuntimeException(stopped); } return result(text); });
        }
        private static LlmResult result(String text) { return new LlmResult(text, null, List.of(), new Usage(1,1), "stop"); }
        @Override public LlmResult chatStreaming(LlmRequest request, java.util.function.Consumer<String> deltas) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet(); var next = outcomes.poll(); assertNotNull(next, "Unexpected extra model call"); return next.apply(request);
        }
    }
    @Configuration
    @Import({RequirementServiceImpl.class, BuildDecisions.class, RequirementCoordinator.class, RequirementProjection.class,
            ProjectLeaseService.class, ProjectMutationLocks.class, FencedProjectFiles.class, ProjectArtifactService.class,
            MessageServiceImpl.class, ConversationProposals.class, AgentRunCreator.class, AgentDispatchService.class, AgentTriggerListener.class, AgentDispatchPoller.class})
    static class Services {
        @Bean AgentProperties props(org.springframework.core.env.Environment env) {
            var p = new AgentProperties(); p.setWorkspaceBasePath(env.getRequiredProperty("app.agent.workspace-base-path"));
            p.getSandbox().setRequired(true); p.getLlm().setApiKey("disposable-dummy"); p.getLlm().setBaseUrl("http://127.0.0.1:1"); p.getModels().setPlan(List.of("fixture-model")); p.setStructuredContracts(true); return p;
        }
        @Bean WorkspaceStorage storage(AgentProperties p) { return new WorkspaceStorage(p); }
        @Bean HostProjectFiles host(WorkspaceStorage s) { return spy(new HostProjectFiles(s)); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean RealtimeService realtime() { return mock(RealtimeService.class); }
        @Bean AgentEvents events() { return mock(AgentEvents.class); }
        @Bean AgentJobQueue queue() { return mock(AgentJobQueue.class); }
        @Bean AgentTurnAdmission admission() { return mock(AgentTurnAdmission.class); }
        @Bean MessageSequence sequence() { return new MessageSequence(); }
        @Bean ControlledModel model() { return new ControlledModel(); }
    }
}
