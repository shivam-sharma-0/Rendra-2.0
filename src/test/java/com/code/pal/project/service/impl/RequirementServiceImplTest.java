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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Model calls take up to 30 s, so they must never run while a transaction holds a pooled DB connection. */
class RequirementServiceImplTest {

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
    private final MessageService messages = mock(MessageService.class);
    private final ProjectArtifactService artifacts = mock(ProjectArtifactService.class);

    private final RequirementServiceImpl service = new RequirementServiceImpl(projects, sessions, questions, model(),
            new AgentProperties(), new ObjectMapper(), artifacts, messages,
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
        modelReply = () -> "[" + question("audience") + "," + question("style") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);

        assertEquals(1, modelCalls.get());
        assertEquals(0, modelCallsInsideATransaction.get(), "the model was called while a DB connection was held");
        assertEquals(2, view.questions().size());
        assertEquals(RequirementStatus.IN_PROGRESS, view.status());
    }

    @Test
    void imagesAttachedToTheOpeningPromptShapeTheQuestionsAndGoWithTheApprovedBuild() {
        List<String> images = List.of("uploads/1-design.png");
        when(messages.openingAttachments(projectId)).thenReturn(images);
        modelReply = () -> "[" + question("audience") + "," + question("visual_style") + "]";

        RequirementDtos.Session view = service.start(7L, projectId);
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("attached 1 image(s)")), "the questions know about the images");
        assertEquals(List.of("audience"), view.questions().stream().map(RequirementDtos.Question::key).toList(),
                "the images decide the look, so it is not asked");

        RequirementQuestion audience = stored.getFirst();
        audience.setSelectedOption("a");
        service.approve(7L, projectId);

        var posted = org.mockito.ArgumentCaptor.forClass(com.code.pal.message.dto.CreateMessageRequest.class);
        org.mockito.Mockito.verify(messages).post(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(projectId), posted.capture());
        assertEquals(images, posted.getValue().attachments(), "the build reads and can use the images");

        var brief = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(artifacts).writeBriefIfAbsent(org.mockito.ArgumentMatchers.eq(projectId), brief.capture());
        assertTrue(brief.getValue().contains("A coffee shop site") && brief.getValue().contains("## Requirements"), brief.getValue());
    }

    @Test
    void theLastAnswerIsReviewedOutsideAnyTransaction() {
        RequirementQuestion only = save("audience");
        modelReply = () -> "{\"requirementsClear\":true,\"questions\":[]}";

        RequirementDtos.Session view = service.answer(7L, projectId, only.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(1, modelCalls.get());
        assertEquals(0, modelCallsInsideATransaction.get(), "the model was called while a DB connection was held");
        assertEquals(RequirementStatus.APPROVAL_REQUIRED, view.status());
    }

    @Test
    void aReviewThatFindsGapsAddsQuestions() {
        RequirementQuestion only = save("audience");
        modelReply = () -> "{\"requirementsClear\":false,\"questions\":[" + question("storage") + "]}";

        RequirementDtos.Session view = service.answer(7L, projectId, only.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(RequirementStatus.IN_PROGRESS, view.status());
        assertEquals(List.of("audience", "storage"), view.questions().stream().map(RequirementDtos.Question::key).toList());
    }

    @Test
    void aReviewDoesNotReopenASessionApprovedWhileTheModelWasThinking() {
        RequirementQuestion only = save("audience");
        modelReply = () -> "{\"requirementsClear\":false,\"questions\":[" + question("storage") + "]}";
        duringModelCall = () -> session.setStatus(RequirementStatus.APPROVED);

        RequirementDtos.Session view = service.answer(7L, projectId, only.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(RequirementStatus.APPROVED, view.status());
        assertEquals(1, view.questions().size(), "no questions are added to an approved session");
    }

    @Test
    void anUnansweredQuestionLeftOpenSkipsTheModel() {
        RequirementQuestion first = save("audience");
        save("style");

        service.answer(7L, projectId, first.getId(), new RequirementDtos.AnswerRequest("a", null));

        assertEquals(0, modelCalls.get());
        assertFalse(session.getStatus() == RequirementStatus.APPROVAL_REQUIRED);
    }

    @Test
    void userMistakesAreClientErrorsNotServerErrors() {
        RequirementQuestion only = save("audience");

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

    private static String question(String key) {
        return "{\"key\":\"" + key + "\",\"prompt\":\"" + key + "?\",\"required\":true,\"recommended\":\"a\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"other\",\"label\":\"Other\"}]}";
    }

    private LlmClient model() {
        return (request, onTextDelta) -> {
            modelCalls.incrementAndGet();
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
