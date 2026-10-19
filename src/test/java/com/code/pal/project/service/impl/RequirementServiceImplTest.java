package com.code.pal.project.service.impl;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.RequirementQuestion;
import com.code.pal.project.domain.RequirementQuestionType;
import com.code.pal.project.domain.RequirementSession;
import com.code.pal.project.domain.RequirementStatus;
import com.code.pal.project.dto.RequirementDtos;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.repository.RequirementQuestionRepository;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Model calls take up to 30 s, so they must never run while a transaction holds a pooled DB connection. */
class RequirementServiceImplTest {
    @Test
    void unavailablePrimaryPlannerUsesOnlyTheConfiguredPlanFallbackOutsideTransactions() {
        var decisions = mock(BuildDecisions.class);
        when(decisions.available(projectId)).thenReturn(new BuildDecisions.Available(true, null));
        service.setBuildDecisions(decisions);
        properties.getModels().setPlan(List.of("openrouter:primary", "nvidia:planning-fallback"));
        modelReply = () -> {
            if (modelCalls.get() == 1) throw new com.code.pal.agent.llm.LlmStreamException(
                    com.code.pal.agent.llm.LlmStreamException.Reason.PROVIDER_ERROR, 503, null);
            return "{\"questions\":[],\"spec\":" + COFFEE + "}";
        };
        var view = service.start(7L, projectId);
        assertEquals(2, modelCalls.get());
        assertEquals(List.of("primary", "planning-fallback"), calledModels);
        assertEquals(0, modelCallsInsideATransaction.get());
        assertTrue(view.complete());
        assertTrue(session.getSpecDraftJson().contains("Coffee"));
    }

    @Test
    void aTodoRequestSkipsGenericDiscoveryAndAutomaticallySelectsItsBackend() {
        var decisions = mock(BuildDecisions.class);
        when(decisions.available(projectId)).thenReturn(new BuildDecisions.Available(true, null));
        service.setBuildDecisions(decisions);
        when(projects.requireCanEdit(7L, projectId)).thenReturn(Project.builder().id(projectId).initialPrompt("Build a complete Todo app").build());
        modelReply = () -> "{\"questions\":[" + question("primary_use_case") + "],\"spec\":" + COFFEE + "}";
        var view = service.start(7L, projectId);
        assertEquals(List.of("platform_storage", "platform_auth"), view.questions().stream().map(RequirementDtos.Question::key).toList());
        assertEquals(List.of("MERN", "NONE"), stored.stream().map(RequirementQuestion::getSelectedOption).toList());
        assertTrue(view.complete());
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, view.status());
        assertEquals(1, modelCalls.get());
    }

    @Test
    void aProviderFailureDoesNotInventABareMinimumQuestionnaire() {
        modelReply = () -> { throw new IllegalStateException("Fixture unavailable"); };
        assertTrue(service.start(7L, projectId).questions().isEmpty());
        assertNull(session.getSpecDraftJson());
    }
    @Test
    void semanticCapabilitiesAreResolvedInTheExistingInterviewCall() {
        var decisions = mock(BuildDecisions.class);
        when(decisions.available(projectId)).thenReturn(new BuildDecisions.Available(true, null));
        service.setBuildDecisions(decisions);
        modelReply = () -> "{\"questions\":[],\"spec\":" + COFFEE.replace("\"title\":\"Coffee\"",
                "\"capabilities\":{\"persistence\":\"SERVER\",\"accounts\":true},\"title\":\"Coffee\"") + "}";
        service.start(7L, projectId);
        assertEquals("MERN", stored.stream().filter(value -> value.getQuestionKey().equals("platform_storage"))
                .findFirst().orElseThrow().getSelectedOption());
        assertEquals("ACCOUNTS", stored.stream().filter(value -> value.getQuestionKey().equals("platform_auth"))
                .findFirst().orElseThrow().getSelectedOption());
        assertEquals(1, modelCalls.get());
    }

    private final UUID projectId = UUID.randomUUID();
    private final ProjectService projects = mock(ProjectService.class);
    private final RequirementSessionRepository sessions = mock(RequirementSessionRepository.class);
    private final RequirementQuestionRepository questions = mock(RequirementQuestionRepository.class);
    private final RequirementSession session = RequirementSession.builder().id(UUID.randomUUID()).projectId(projectId).build();
    private final List<RequirementQuestion> stored = new ArrayList<>();
    private final AtomicInteger modelCallsInsideATransaction = new AtomicInteger();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private Supplier<String> modelReply = () -> "[]";
    private Runnable duringModelCall = () -> { };
    private final List<String> prompts = new ArrayList<>();
    private final List<String> calledModels = new ArrayList<>();
    private final MessageService messages = mock(MessageService.class);
    private final ProjectArtifactService artifacts = mock(ProjectArtifactService.class);

    private final AgentProperties properties = new AgentProperties();
    private final RequirementServiceImpl service = new RequirementServiceImpl(projects, sessions, questions, model(),
            properties, new ObjectMapper(), artifacts, messages,
            new FakeTransactions());

    RequirementServiceImplTest() {
        when(projects.requireCanEdit(7L, projectId)).thenReturn(Project.builder().id(projectId).initialPrompt("A coffee shop site").build());
        when(sessions.findByProjectId(projectId)).thenReturn(Optional.of(session));
        when(questions.findBySessionIdOrderBySequenceAsc(session.getId())).thenAnswer(call ->
                stored.stream().sorted(Comparator.comparingInt(RequirementQuestion::getSequence)).toList());
        when(questions.save(any(RequirementQuestion.class))).thenAnswer(call -> {
            RequirementQuestion question = call.getArgument(0);
            question.setId(UUID.randomUUID());
            stored.add(question);
            return question;
        });
        when(questions.findByIdAndSessionId(any(), any())).thenAnswer(call ->
                stored.stream().filter(question -> question.getId().equals(call.getArgument(0))).findFirst());
    }

    @Test
    void questionsAreGeneratedOutsideAnyTransaction() {
        modelReply = () -> "[" + question("purpose") + "," + question("features") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertEquals(1, modelCalls.get());
        assertEquals(0, modelCallsInsideATransaction.get(), "the model was called while a DB connection was held");
        assertEquals(2, view.questions().size());
        assertEquals(RequirementStatus.IN_PROGRESS, view.status());
    }

    @Test
    void theSpecPromptBudgetsTwentyFourActionsAcrossAllScenariosWithoutDroppingChecks() {
        service.start(7L, projectId);

        String prompt = String.join("\n", prompts);
        assertTrue(prompt.contains("at most 24 browser actions across all acceptance scenarios combined"));
        assertTrue(prompt.contains("not 24 per scenario"));
        assertTrue(prompt.contains("never truncate required checks"));
        assertEquals(1, modelCalls.get(), "The budget instruction must use the existing spec call");
    }

    @Test
    void imagesAttachedToTheOpeningPromptShapeTheQuestionsAndGoWithTheApprovedBuild() {
        List<String> images = List.of("uploads/1-design.png");
        when(messages.openingAttachments(projectId)).thenReturn(images);
        modelReply = () -> "[" + question("purpose") + "," + question("visual_style") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("attached 1 image(s)")), "the questions know about the images");
        assertEquals(List.of("purpose"), view.questions().stream().map(RequirementDtos.Question::key).toList(),
                "the images decide the look, so it is not asked");

        RequirementQuestion purpose = stored.getFirst();
        purpose.setSelectedOption("a");
        service.approve(7L, projectId);

        var posted = org.mockito.ArgumentCaptor.forClass(com.code.pal.message.dto.CreateMessageRequest.class);
        org.mockito.Mockito.verify(messages).post(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(projectId), posted.capture());
        assertEquals(images, posted.getValue().attachments(), "the build reads and can use the images");

        var brief = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(artifacts).writeBriefIfAbsent(org.mockito.ArgumentMatchers.eq(projectId), brief.capture());
        assertTrue(brief.getValue().contains("A coffee shop site") && brief.getValue().contains("## Requirements"), brief.getValue());
    }

    @Test
    void completeAnswersGoStraightToApprovalWithoutAModelCall() {
        RequirementQuestion only = save("purpose");

        RequirementDtos.Session view = service.answer(7L, projectId, only.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(0, modelCalls.get(), "a second model review of the answers is not needed");
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, view.status());
    }

    @Test
    void aClearRequestAsksNothingAndPreselectsStorageAndSignIn() {
        service.setBuildDecisions(new com.code.pal.project.capability.BuildDecisions(sessions,
                mock(com.code.pal.project.supabase.SupabaseConnectionRepository.class), new AgentProperties(), new ObjectMapper()));
        modelReply = () -> "[]";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertEquals(1, modelCalls.get());
        assertEquals(List.of("platform_storage", "platform_auth"), view.questions().stream().map(RequirementDtos.Question::key).toList());
        assertEquals(List.of("NONE", "NONE"), stored.stream().map(RequirementQuestion::getSelectedOption).toList());
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, view.status());
    }

    @Test
    void atMostTwoQuestionsAndNeverOneAboutTheLook() {
        modelReply = () -> "[" + question("visual_style") + "," + question("purpose") + "," + question("features") + ","
                + question("data") + "," + question("pages") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertEquals(List.of("purpose", "features"), view.questions().stream().map(RequirementDtos.Question::key).toList());
    }

    private static final String COFFEE = """
            {"title":"Coffee","pages":[{"id":"home","path":"/","featureIds":["brews","loading_state"]}],
             "features":[{"id":"brews","description":"List brews"},{"id":"loading_state","description":"Spinner"}],
             "acceptance":[{"id":"lists","featureId":"brews","steps":[{"kind":"ASSERT_TEXT","selector":"h1","value":"Coffee"}]}]}""";

    @Test
    void anUncheckableFeatureLeavesTheDraftInsteadOfVoidingEveryCheck() {
        modelReply = () -> "{\"questions\":[],\"spec\":" + COFFEE + "}";

        service.start(7L, projectId);

        assertTrue(session.getSpecDraftJson().contains("\"lists\""), session.getSpecDraftJson());
        assertTrue(session.getSpecDraftJson().contains("loading_state"), "uncheckable scope is preserved separately");
        var review=service.get(7L,projectId).review();
        assertEquals(2,review.features().size());
        assertFalse(review.features().get(1).checkPlanned());
    }

    @Test
    void oneCallReturnsTheQuestionsAndASpecDraft() {
        properties.setStructuredContracts(true);
        modelReply = () -> "```json\n{\"questions\":[" + question("purpose") + "],\"spec\":" + COFFEE + "}\n```";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertEquals(List.of("purpose"), view.questions().stream().map(RequirementDtos.Question::key).toList());
        assertTrue(session.getSpecDraftJson().contains("\"brews\""), session.getSpecDraftJson());
        assertEquals(1, modelCalls.get());
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("acceptance")), "the spec is requested");
    }

    @Test
    void theSpecIsDraftedOnTheDefaultPathTooForBehaviourChecks() {
        modelReply = () -> "{\"questions\":[" + question("purpose") + "],\"spec\":" + COFFEE + "}";

        service.start(7L, projectId);

        assertTrue(session.getSpecDraftJson().contains("\"brews\""), session.getSpecDraftJson());
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("acceptance")));
    }

    @Test
    void anObjectWithNoQuestionsAsksNothingAndAMissingSpecIsNotInvented() {
        modelReply = () -> "{\"questions\":[]}";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertTrue(view.questions().isEmpty());
        assertNull(session.getSpecDraftJson());
    }

    @Test
    void audienceAndOpenEndedFeatureQuestionsAreNeverAsked() {
        modelReply = () -> "[{\"key\":\"target\",\"prompt\":\"Who is the primary audience for the site?\",\"required\":true,\"recommended\":\"a\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"other\",\"label\":\"Other\"}]}," + question("extras").replace("extras?", "Any additional features you would like?") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertTrue(view.questions().isEmpty(), "a sensible default answers both, so nothing blocks the build");
    }

    @Test
    void aFencedEmptyListMeansNoQuestions() {
        modelReply = () -> "```json\n[]\n```";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertTrue(view.questions().isEmpty());
        assertEquals(1, modelCalls.get());
    }

    @Test
    void storageIsPreselectedOnlyAfterTheLastProductAnswer() {
        var decisions = mock(BuildDecisions.class);
        when(decisions.available(projectId)).thenReturn(new BuildDecisions.Available(true, null));
        service.setBuildDecisions(decisions);
        modelReply = () -> "[{\"key\":\"features\",\"prompt\":\"What should it do?\",\"required\":true,\"recommended\":\"a\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"Save my tasks\"},{\"id\":\"b\",\"label\":\"Just a page\"}]}]";

        service.start(7L, projectId);
        RequirementQuestion storage = stored.stream().filter(q -> q.getQuestionKey().equals("platform_storage")).findFirst().orElseThrow();
        assertNull(storage.getSelectedOption(), "nothing is preselected while a product question is open");

        RequirementDtos.Session view = service.answer(7L, projectId, stored.getFirst().getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals("MERN", storage.getSelectedOption());
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, view.status());
        assertEquals(1, modelCalls.get());
    }

    @Test
    void anUnansweredQuestionLeftOpenSkipsTheModel() {
        RequirementQuestion first = save("purpose");
        save("style");

        service.answer(7L, projectId, first.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(0, modelCalls.get());
        assertFalse(session.getStatus() == RequirementStatus.APPROVAL_REQUIRED);
    }

    @Test
    void userMistakesAreClientErrorsNotServerErrors() {
        RequirementQuestion only = save("purpose");

        var empty = assertThrows(com.code.pal.project.exception.InvalidRequirementAnswerException.class,
                () -> service.answer(7L, projectId, only.getId(), new RequirementDtos.AnswerRequest(" ", null)));
        var early = assertThrows(com.code.pal.project.exception.WorkflowStageException.class,
                () -> service.approve(7L, projectId));

        assertEquals(400, empty.status().value());
        assertEquals(409, early.status().value());
    }

    private RequirementQuestion save(String key) {
        return questions.save(RequirementQuestion.builder().sessionId(session.getId()).sequence(stored.size() + 1)
                .questionKey(key).prompt(key + "?").type(RequirementQuestionType.SINGLE_SELECT).required(true)
                .optionsJson("[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}]").recommendedOption("a").build());
    }

    @Test void capabilitiesReplaceModelCloudChoicesAndApprovalUsesTheExplicitSelection() {
        var decisions = new com.code.pal.project.capability.BuildDecisions(sessions,
                mock(com.code.pal.project.supabase.SupabaseConnectionRepository.class),
                new AgentProperties(), new ObjectMapper());
        service.setBuildDecisions(decisions);
        modelReply = () -> "[" + question("storage") + "," + question("purpose") + "]";
        var view = service.start(7L, projectId);
        assertEquals(List.of("purpose", "platform_storage", "platform_auth"), view.questions().stream().map(RequirementDtos.Question::key).toList());
        var storage = stored.stream().filter(q -> q.getQuestionKey().equals("platform_storage")).findFirst().orElseThrow();
        assertFalse(storage.getOptionsJson().contains("SUPABASE") || storage.getOptionsJson().contains("MERN") || storage.getOptionsJson().contains("saved_database"));
        stored.forEach(q -> q.setSelectedOption(q.getQuestionKey().equals("purpose") ? "a" : q.getQuestionKey().equals("platform_storage") ? "BROWSER" : "NONE"));
        service.approve(7L, projectId);
        assertTrue(session.getBuildDecisionJson().contains("BROWSER"));
    }

    @Test void modelCannotSupplyPlatformOptionIdsOrActivateAnUnavailableConnection() {
        var decisions = new com.code.pal.project.capability.BuildDecisions(sessions,
                mock(com.code.pal.project.supabase.SupabaseConnectionRepository.class), new AgentProperties(), new ObjectMapper());
        service.setBuildDecisions(decisions);
        modelReply = () -> "[" + question("platform_storage") + "]";
        service.start(7L, projectId);
        var storage = stored.stream().filter(q -> q.getQuestionKey().equals("platform_storage")).findFirst().orElseThrow();
        assertThrows(com.code.pal.project.exception.InvalidRequirementAnswerException.class,
                () -> service.answer(7L, projectId, storage.getId(), new RequirementDtos.AnswerRequest("SUPABASE", null)));
        assertThrows(com.code.pal.project.exception.InvalidRequirementAnswerException.class,
                () -> service.answer(7L, projectId, storage.getId(), new RequirementDtos.AnswerRequest("BROWSER", "Cloud")));
        assertEquals(1, modelCalls.get());
    }

    @Test void legacyCloudApprovalReopensForAnExplicitDecision() {
        save("storage").setSelectedOption("a");
        session.setStatus(RequirementStatus.APPROVED);
        service.setBuildDecisions(new com.code.pal.project.capability.BuildDecisions(sessions,
                mock(com.code.pal.project.supabase.SupabaseConnectionRepository.class), new AgentProperties(), new ObjectMapper()));
        var view = service.start(7L, projectId);
        assertEquals(RequirementStatus.IN_PROGRESS, view.status());
        assertFalse(view.complete());
        assertEquals(0, modelCalls.get());
    }

    @Test void malformedModelQuestionsCannotOverflowPersistentKeysOrCreateAmbiguousOptions() {
        modelReply = () -> "[" + question("x".repeat(81)) + "," + question("purpose") + "," + question("PURPOSE") + "]";
        var view = service.start(7L, projectId);
        assertEquals(1, view.questions().size());
        assertEquals("purpose", view.questions().getFirst().key());
        assertEquals(1, modelCalls.get());
    }

    private static String question(String key) {
        return "{\"key\":\"" + key + "\",\"prompt\":\"" + key + "?\",\"required\":true,\"recommended\":\"a\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"other\",\"label\":\"Other\"}]}";
    }

    private LlmClient model() {
        return (request, onTextDelta) -> {
            modelCalls.incrementAndGet();
            calledModels.add(request.model());
            request.messages().forEach(message -> prompts.add(message.content()));
            if (TransactionSynchronizationManager.isActualTransactionActive()) modelCallsInsideATransaction.incrementAndGet();
            duringModelCall.run();
            return new LlmClient.LlmResult(modelReply.get(), null, List.of(), new LlmClient.Usage(1, 1), "stop");
        };
    }

    /** Marks transactions active exactly like a real JPA transaction manager, without a database. */
    private static final class FakeTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
