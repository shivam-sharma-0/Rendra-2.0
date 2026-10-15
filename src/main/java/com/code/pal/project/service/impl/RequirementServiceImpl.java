package com.code.pal.project.service.impl;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.brief.ProjectBriefFile;
import com.code.pal.project.domain.*;
import com.code.pal.project.dto.RequirementDtos;
import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.exception.InvalidRequirementAnswerException;
import com.code.pal.project.exception.ProjectNotFoundException;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.RequirementQuestionRepository;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.project.service.RequirementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class RequirementServiceImpl implements RequirementService {
    /**
     * These calls run inside the user's HTTP request and already fall back to deterministic questions,
     * so a slow provider must not hold the request open.
     */
    private static final java.time.Duration LLM_BUDGET = java.time.Duration.ofSeconds(30);

    private final ProjectService projects;
    private final RequirementSessionRepository sessions;
    private final RequirementQuestionRepository questions;
    private final LlmClient llm;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectArtifactService artifacts;
    private final MessageService messages;
    /**
     * Model calls take up to {@link #LLM_BUDGET}; holding a pooled DB connection that long would let a
     * few slow requests starve the whole app. So start/answer read and write in short transactions
     * and call the model between them, with no transaction open.
     */
    private final TransactionTemplate tx;
    private com.code.pal.project.capability.BuildDecisions buildDecisions;
    private com.code.pal.project.requirement.RequirementCoordinator coordinator;
    private com.code.pal.project.requirement.RequirementProjection projection;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCoordination(com.code.pal.project.requirement.RequirementCoordinator coordinator,
                                com.code.pal.project.requirement.RequirementProjection projection) {
        this.coordinator = java.util.Objects.requireNonNull(coordinator);
        this.projection = java.util.Objects.requireNonNull(projection);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setBuildDecisions(com.code.pal.project.capability.BuildDecisions buildDecisions) {
        this.buildDecisions = java.util.Objects.requireNonNull(buildDecisions);
    }

    public RequirementServiceImpl(ProjectService projects, RequirementSessionRepository sessions,
                                  RequirementQuestionRepository questions, LlmClient llm, AgentProperties properties,
                                  ObjectMapper objectMapper, ProjectArtifactService artifacts, MessageService messages,
                                  PlatformTransactionManager transactionManager) {
        this.projects = projects;
        this.sessions = sessions;
        this.questions = questions;
        this.llm = llm;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.artifacts = artifacts;
        this.messages = messages;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(10);
    }

    @Override
    @Transactional(readOnly = true)
    public RequirementDtos.Session get(Long userId, UUID projectId) {
        projects.requireAccessible(userId, projectId);
        return sessions.findByProjectId(projectId).map(this::view)
                .orElseGet(() -> new RequirementDtos.Session(null, projectId, RequirementStatus.DRAFT, List.of(), false, null));
    }

    @Override
    public RequirementDtos.Session start(Long userId, UUID projectId) {
        Interview interview = tx.execute(status -> {
            lockRequirements(projectId);
            Project project = projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseGet(() -> sessions.saveAndFlush(
                    RequirementSession.builder().projectId(projectId).sourcePrompt(Optional.ofNullable(project.getInitialPrompt()).orElse("")).build()));
            if (!questions.findBySessionIdOrderBySequenceAsc(session.getId()).isEmpty()) {
                ensureCapabilityQuestions(session);
                return new Interview(null, session.getId(), session.getAnswerRevision(), null, view(session));
            }
            var claim = coordinator == null ? null : coordinator.claim(session.getId(), session.getAnswerRevision(), "QUESTIONS", userId);
            session.setStatus(RequirementStatus.IN_PROGRESS);
            return new Interview(coordinator != null && claim == null ? null : ProjectBrief.of(project, messages.openingAttachments(projectId)),
                    session.getId(), session.getAnswerRevision(), claim, view(session));
        });
        RequirementDtos.Session result = interview.view();
        if (interview.project() != null) {
            List<QuestionDraft> generated;
            if (interview.claim() != null && interview.claim().fallback()) generated = defaults(interview.project().initialPrompt());
            else try (LlmCallContext.Scope call = LlmCallContext.bind(new LlmCallContext(userId, projectId, null, CallPurpose.REQUIREMENTS, false))) {
                generated = generate(interview.project());
            }
            List<QuestionDraft> drafts = interview.project().attachments().isEmpty() ? generated
                    : generated.stream().filter(draft -> !isAboutTheLook(draft)).toList();
            result = tx.execute(status -> {
                lockRequirements(projectId);
                projects.requireCanEdit(userId, projectId);
                RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
                boolean current = session.getId().equals(interview.session()) && session.getAnswerRevision() == interview.revision();
                boolean owned = coordinator == null || coordinator.finish(interview.session(), interview.revision(), "QUESTIONS", interview.claim(), current ? "COMPLETE" : "STALE");
                if (current && owned && questions.findBySessionIdOrderBySequenceAsc(session.getId()).isEmpty()) {
                    saveQuestions(session, capabilityQuestions(projectId, drafts.isEmpty() ? defaults(interview.project().initialPrompt()) : drafts));
                    ensureCapabilityQuestions(session);
                }
                return view(session);
            });
        }
        // Poll/reload can recover an expired assessment without another provider send.
        if (coordinator != null && result.complete() && result.status() == RequirementStatus.IN_PROGRESS) {
            RequirementDtos.Question last = result.questions().getLast();
            return answer(userId, projectId, last.id(), new RequirementDtos.AnswerRequest(last.selectedOption(), last.otherText(), result.revision()));
        }
        if (projection != null && "PENDING".equals(result.artifactStatus())) {
            UUID id = tx.execute(status -> sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new).getProjectionId());
            projection.tryProject(id);
            return tx.execute(status -> view(sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new)));
        }
        return result;
    }

    @Override
    public RequirementDtos.Session answer(Long userId, UUID projectId, UUID questionId, RequirementDtos.AnswerRequest request) {
        Recorded recorded = tx.execute(status -> {
            lockRequirements(projectId);
            Project project = projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            RequirementQuestion question = questions.findByIdAndSessionId(questionId, session.getId()).orElseThrow(ProjectNotFoundException::new);
            String selected = clean(request.selectedOption(), 80);
            String other = clean(request.otherText(), 4_000);
            if (selected != null && !optionExists(question, selected)) throw new InvalidRequirementAnswerException("Choose an option offered by this question");
            if (question.getQuestionKey().startsWith("platform_") && other != null) throw new InvalidRequirementAnswerException("Storage and sign-in need a supported option");
            if (question.isRequired() && selected == null && other == null) throw new InvalidRequirementAnswerException("This requirement question needs an answer");
            boolean changed = !Objects.equals(question.getSelectedOption(), selected) || !Objects.equals(question.getOtherText(), other);
            if (coordinator != null && (request.revision() == null || request.revision() != session.getAnswerRevision())) {
                throw new WorkflowStageException("Requirements changed; reload before editing answers");
            }
            if (changed) {
                if (session.getStatus() == RequirementStatus.APPROVED && coordinator != null) {
                    coordinator.requireNoActiveBuild(projectId);
                    projects.advanceLifecycle(userId, projectId, ProjectStage.REQUIREMENTS);
                }
                question.setSelectedOption(selected);
                question.setOtherText(other);
                session.setAnswerRevision(Math.addExact(session.getAnswerRevision(), 1));
                session.setBuildDecisionJson(null);
                session.setApprovedRevision(null);
                session.setApprovedAt(null);
                session.setApprovedBy(null);
                session.setProjectionId(null);
                session.setStatus(RequirementStatus.IN_PROGRESS);
            }
            if (!complete(session) || session.getStatus() == RequirementStatus.APPROVED || session.getStatus() == RequirementStatus.APPROVAL_REQUIRED) {
                return new Recorded(view(session), null, null, session.getId(), session.getAnswerRevision(), null);
            }
            var claim = coordinator == null ? null : coordinator.claim(session.getId(), session.getAnswerRevision(), "ASSESSMENT", userId);
            if (coordinator != null && claim == null) return new Recorded(view(session), null, null, session.getId(), session.getAnswerRevision(), null);
            return new Recorded(null, ProjectBrief.of(project, List.of()), answerSummary(questions.findBySessionIdOrderBySequenceAsc(session.getId())),
                    session.getId(), session.getAnswerRevision(), claim);
        });
        if (recorded.view() != null) return recorded.view();
        Assessment assessment;
        if (recorded.claim() != null && recorded.claim().fallback()) assessment = new Assessment(true, List.of(), true);
        else try (LlmCallContext.Scope call = LlmCallContext.bind(new LlmCallContext(userId, projectId, null, CallPurpose.REQUIREMENTS, false))) {
            assessment = assess(recorded.project(), recorded.summary());
        } catch (RuntimeException error) {
            log.warn("requirement_assessment_fallback project={} reason={}", projectId, error.getClass().getSimpleName());
            assessment = new Assessment(true, List.of(), true);
        }
        Assessment verdict = assessment;
        return tx.execute(status -> {
            lockRequirements(projectId);
            projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            boolean current = session.getId().equals(recorded.session()) && session.getAnswerRevision() == recorded.revision()
                    && session.getStatus() != RequirementStatus.APPROVED && complete(session);
            boolean owned = coordinator == null || coordinator.finish(recorded.session(), recorded.revision(), "ASSESSMENT", recorded.claim(), current ? verdict.fallback() ? "FALLBACK" : "COMPLETE" : "STALE");
            if (current && owned) refineOrApprove(session, verdict);
            return view(session);
        });
    }

    private void lockRequirements(UUID project) { if (coordinator != null) coordinator.lock(project); }

    /** Applies a review of the complete answer set, adding only genuinely missing decisions. */
    private void refineOrApprove(RequirementSession session, Assessment assessment) {
        List<RequirementQuestion> answered = questions.findBySessionIdOrderBySequenceAsc(session.getId());
        if (!assessment.clear() && !assessment.questions().isEmpty()) {
            Set<String> knownKeys = answered.stream().map(RequirementQuestion::getQuestionKey)
                    .map(key -> key.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            int remaining = Math.max(0, 8 - answered.size());
            List<QuestionDraft> additional = assessment.questions().stream()
                    .filter(item -> buildDecisions == null || !isCapabilityQuestion(item))
                    .filter(item -> knownKeys.add(item.key().toLowerCase(Locale.ROOT)))
                    .limit(remaining)
                    .toList();
            if (!additional.isEmpty()) {
                saveQuestions(session, additional, answered.size());
                session.setStatus(RequirementStatus.IN_PROGRESS);
                return;
            }
        }
        session.setStatus(RequirementStatus.APPROVAL_REQUIRED);
    }

    private String answerSummary(List<RequirementQuestion> answered) {
        StringBuilder summary = new StringBuilder();
        for (RequirementQuestion question : answered) {
            String answer = answerLabel(question);
            summary.append("- ").append(question.getQuestionKey()).append(": ")
                    .append(answer == null ? "Not specified" : answer).append('\n');
        }
        return summary.toString();
    }

    private Assessment assess(ProjectBrief project, String summary) {
        String prompt = """
                Return only valid JSON object with this shape:
                {"requirementsClear":true|false,"questions":[{"key":"...","prompt":"...","required":true,"recommended":"...","options":[{"id":"...","label":"...","description":"..."}]}]}
                Decide whether the approved product requirements are specific enough to build without guessing.
                Set requirementsClear=true and questions=[] when they are sufficient. If not, return at most two
                blocking questions. Every question needs at least two concrete options and an option with id "other".
                Do not repeat a decision already answered. User's original request: %s
                Current answers:
                %s
                """.formatted(project.initialPrompt(), summary);
        AgentProperties.ModelSelection selection = properties.modelFor(ProjectType.PLAN);
        LlmResult result = llm.chat(new LlmRequest(selection.provider(), selection.model(),
                List.of(LlmMessage.system("You are a strict product requirements reviewer."), LlmMessage.user(prompt)), List.of(), 0.0, LLM_BUDGET)
                .withMaxTokens(properties.getLlm().getMaxOutputTokens()));
        JsonNode root = objectMapper.readTree(stripJsonFence(result.content()));
        if (!root.isObject()) throw new IllegalArgumentException("Requirement assessment was not an object");
        return new Assessment(root.path("requirementsClear").asBoolean(false), parseQuestionDrafts(root.path("questions")));
    }

    @Override
    public RequirementDtos.Session approve(Long userId, UUID projectId) { return approve(userId, projectId, null); }

    @Override
    public RequirementDtos.Session approve(Long userId, UUID projectId, Long revision) {
        RequirementDtos.Session approved = tx.execute(status -> {
            lockRequirements(projectId);
            Project project = projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            if (coordinator != null && (revision == null || revision != session.getAnswerRevision())) {
                throw new WorkflowStageException("Requirements changed; review the current answers before approving");
            }
            if (session.getStatus() == RequirementStatus.APPROVED) return view(session);
            if (!complete(session)) throw new WorkflowStageException("Answer all required requirement questions first");
            if (coordinator != null && session.getStatus() != RequirementStatus.APPROVAL_REQUIRED) {
                throw new WorkflowStageException("Wait for review of this answer revision before approving");
            }
            if (coordinator != null) coordinator.requireNoActiveBuild(projectId);
            List<RequirementQuestion> answered = questions.findBySessionIdOrderBySequenceAsc(session.getId());
            if (buildDecisions != null) {
                var choice = buildDecisions.approve(answered, buildDecisions.available(projectId));
                session.setBuildDecisionJson(buildDecisions.encode(choice));
            }
            session.setStatus(RequirementStatus.APPROVED);
            session.setApprovedRevision(session.getAnswerRevision());
            session.setApprovedAt(Instant.now());
            session.setApprovedBy(userId);
            projects.advanceLifecycle(userId, projectId, ProjectStage.DESIGN);
            String brief = ProjectBriefFile.initial(project.getName(), project.getInitialPrompt(),
                    answered.stream().map(q -> new ProjectBriefFile.Requirement(q.getPrompt(), answerLabel(q))).toList(),
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            if (projection != null) {
                sessions.flush();
                Map<String,Object> manifest = new LinkedHashMap<>();
                manifest.put("kind", "requirements"); manifest.put("sessionId", session.getId());
                manifest.put("revision", session.getAnswerRevision()); manifest.put("buildDecision", objectMapper.readTree(session.getBuildDecisionJson()));
                manifest.put("answers", answered.stream().map(q -> new ProjectBriefFile.Requirement(q.getPrompt(), answerLabel(q))).toList());
                session.setProjectionId(projection.enqueue(session.getId(), projectId, session.getAnswerRevision(),
                        new com.code.pal.project.requirement.RequirementProjection.Payload(write(manifest), brief)));
                sessions.flush();
            }
            String suffix = UUID.randomUUID().toString().replace("-", "");
            List<String> attachments = messages.openingAttachments(projectId);
            messages.post(userId, projectId, new CreateMessageRequest(
                    "req_" + suffix.substring(0, 27), approvedBuildPrompt(answered), ProjectType.BUILD,
                    "ai_" + UUID.randomUUID().toString().replace("-", "").substring(0, 27), attachments.isEmpty() ? null : attachments));
            if (projection == null) { // focused constructor fixture only; managed approval never writes files in its DB transaction
                artifacts.writeRequirements(projectId, session.getId(), answered);
                artifacts.writeBriefIfAbsent(projectId, brief);
            }
            return view(session);
        });
        if (projection != null) {
            UUID id = tx.execute(status -> sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new).getProjectionId());
            projection.tryProject(id);
            return tx.execute(status -> view(sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new)));
        }
        return approved;
    }

    private String approvedBuildPrompt(List<RequirementQuestion> answered) {
        StringBuilder result = new StringBuilder("Build the requested project now. Use the approved requirements below as the source of truth.\n\nApproved requirements:\n");
        for (RequirementQuestion question : answered) {
            String answer = answerLabel(question);
            result.append("- ").append(question.getPrompt()).append(": ").append(answer == null ? "Not specified" : answer).append('\n');
        }
        return result.toString();
    }

    private String answerLabel(RequirementQuestion question) {
        if (question.getOtherText() != null && !question.getOtherText().isBlank()) return question.getOtherText();
        if (question.getSelectedOption() == null) return null;
        try {
            JsonNode options = objectMapper.readTree(question.getOptionsJson());
            if (options.isArray()) for (JsonNode option : options) {
                if (question.getSelectedOption().equals(option.path("id").asText())) return option.path("label").asText(question.getSelectedOption());
            }
        } catch (RuntimeException ignored) {
            // Keep the stable option id when an old or malformed question cannot be decoded.
        }
        return question.getSelectedOption();
    }

    private boolean complete(RequirementSession session) {
        List<RequirementQuestion> answered = questions.findBySessionIdOrderBySequenceAsc(session.getId());
        return !answered.isEmpty() && answered.stream()
                .filter(RequirementQuestion::isRequired)
                .allMatch(q -> (q.getSelectedOption() != null && !q.getSelectedOption().isBlank())
                        || (q.getOtherText() != null && !q.getOtherText().isBlank()));
    }

    private RequirementDtos.Session view(RequirementSession session) {
        List<RequirementDtos.Question> result = questions.findBySessionIdOrderBySequenceAsc(session.getId()).stream().map(this::question).toList();
        return new RequirementDtos.Session(session.getId(), session.getProjectId(), session.getStatus(), result,
                !result.isEmpty() && result.stream().filter(RequirementDtos.Question::required).allMatch(q -> nonBlank(q.selectedOption()) || nonBlank(q.otherText())), session.getApprovedAt(),
                session.getAnswerRevision(), coordinator != null && coordinator.processing(session.getId(), session.getAnswerRevision()),
                coordinator == null ? "NONE" : coordinator.projectionState(session.getProjectionId()),
                coordinator == null ? "NONE" : coordinator.reviewState(session.getId(), session.getAnswerRevision()));
    }

    private RequirementDtos.Question question(RequirementQuestion q) {
        try {
            JsonNode root = objectMapper.readTree(q.getOptionsJson());
            List<RequirementDtos.Option> options = new ArrayList<>();
            if (root.isArray()) for (JsonNode node : root) {
                String id = node.path("id").asText("");
                options.add(new RequirementDtos.Option(id, node.path("label").asText(id), node.path("description").asText(""), id.equals(q.getRecommendedOption())));
            }
            return new RequirementDtos.Question(q.getId(), q.getSequence(), q.getQuestionKey(), q.getPrompt(), q.getType(), q.isRequired(), options,
                    q.getRecommendedOption(), q.getSelectedOption(), q.getOtherText());
        } catch (RuntimeException e) {
            return new RequirementDtos.Question(q.getId(), q.getSequence(), q.getQuestionKey(), q.getPrompt(), q.getType(), q.isRequired(), List.of(),
                    q.getRecommendedOption(), q.getSelectedOption(), q.getOtherText());
        }
    }

    private void saveQuestions(RequirementSession session, List<QuestionDraft> drafts) {
        saveQuestions(session, drafts, 0);
    }

    private boolean optionExists(RequirementQuestion question, String selected) {
        try {
            JsonNode options = objectMapper.readTree(question.getOptionsJson());
            if (options.isArray()) for (JsonNode option : options) {
                if (selected.equals(option.path("id").asText())) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private List<QuestionDraft> capabilityQuestions(UUID projectId, List<QuestionDraft> generated) {
        if (buildDecisions == null) return generated; // constructor-only focused fixture; managed wiring is mandatory
        List<QuestionDraft> result = new ArrayList<>(generated.stream()
                .filter(draft -> !isCapabilityQuestion(draft)).limit(6).toList());
        var available = buildDecisions.available(projectId);
        List<OptionDraft> storage = new ArrayList<>(List.of(
                o("NONE", "No saved data", "Data lasts only during this visit"),
                o("BROWSER", "This browser only", "Saved locally on this device; no shared accounts or cross-device sync")));
        if (available.supabaseConnected()) storage.add(o("SUPABASE", "Connected Supabase", "Use this project's connected database; schema changes need your approval"));
        if (available.mernConfigured()) storage.add(o("MERN", "App backend + MongoDB", "React and Express with a project database; server/database readiness will be checked"));
        result.add(q(com.code.pal.project.capability.BuildDecisions.STORAGE_KEY,
                "Where should data be saved? Cloud storage needs a specific connected service or app backend.", true, "BROWSER", List.copyOf(storage)));
        result.add(q(com.code.pal.project.capability.BuildDecisions.AUTH_KEY, "Should people sign in with real accounts?", true, "NONE",
                List.of(o("NONE", "No sign-in", "Use the app without accounts"),
                        o("ACCOUNTS", "Real accounts", "Requires connected Supabase or the app backend; browser-only sign-in is insufficient"))));
        return List.copyOf(result);
    }

    private void ensureCapabilityQuestions(RequirementSession session) {
        if (buildDecisions == null) return;
        List<RequirementQuestion> existing = questions.findBySessionIdOrderBySequenceAsc(session.getId());
        Set<String> keys = existing.stream().map(RequirementQuestion::getQuestionKey).collect(java.util.stream.Collectors.toSet());
        List<QuestionDraft> canonical = capabilityQuestions(session.getProjectId(), List.of());
        boolean invalid = false;
        for (QuestionDraft draft : canonical) {
            for (RequirementQuestion question : existing) {
                if (!draft.key().equals(question.getQuestionKey())) continue;
                question.setOptionsJson(write(draft.options()));
                question.setRecommendedOption(draft.recommended());
                if (question.getSelectedOption() != null && !optionExists(question, question.getSelectedOption())) {
                    question.setSelectedOption(null);
                    invalid = true;
                }
            }
        }
        List<QuestionDraft> missing = canonical.stream()
                .filter(draft -> !keys.contains(draft.key())).toList();
        if (!missing.isEmpty() || invalid || (session.getStatus() == RequirementStatus.APPROVED
                && buildDecisions.requiresConfirmation(session.getProjectId()))) {
            saveQuestions(session, missing, existing.stream().mapToInt(RequirementQuestion::getSequence).max().orElse(0));
            session.setAnswerRevision(Math.addExact(session.getAnswerRevision(), 1));
            session.setStatus(RequirementStatus.IN_PROGRESS);
            session.setBuildDecisionJson(null);
            session.setApprovedRevision(null);
            session.setProjectionId(null);
            session.setApprovedAt(null);
            session.setApprovedBy(null);
        }
    }

    private static boolean isCapabilityQuestion(QuestionDraft draft) {
        String key = draft.key().toLowerCase(Locale.ROOT);
        return key.equals("storage") || key.equals("database") || key.equals("backend") || key.equals("auth")
                || key.equals("authentication") || key.equals("sign_in") || key.startsWith("platform_");
    }

    private void saveQuestions(RequirementSession session, List<QuestionDraft> drafts, int sequenceOffset) {
        for (int i = 0; i < drafts.size(); i++) {
            QuestionDraft draft = drafts.get(i);
            questions.save(RequirementQuestion.builder().sessionId(session.getId()).sequence(sequenceOffset + i + 1).questionKey(draft.key())
                    .prompt(draft.prompt()).type(RequirementQuestionType.SINGLE_SELECT).required(draft.required())
                    .optionsJson(write(draft.options())).recommendedOption(draft.recommended()).build());
        }
    }

    /**
     * Images are read by the build itself (a vision model takes too long for this request), so the
     * questions only need to know they exist and leave what they show to them.
     */
    private static String attachmentNote(List<String> attachments) {
        if (attachments.isEmpty()) return "";
        return "\nThe user also attached " + attachments.size() + " image(s) (screenshots or mock-ups) that the build will "
                + "follow for layout and visual style: do not ask about visual style or layout, ask about what images cannot show.";
    }

    private static boolean isAboutTheLook(QuestionDraft draft) {
        String text = (draft.key() + " " + draft.prompt()).toLowerCase(java.util.Locale.ROOT).replaceAll("[_-]", " ");
        return text.matches(".*\\b(visual|style|look|theme|colou?rs?|layout)\\b.*");
    }

    private List<QuestionDraft> generate(ProjectBrief project) {
        try {
            AgentProperties.ModelSelection selection = properties.modelFor(ProjectType.PLAN);
            String prompt = """
                    Return only valid JSON array. Create 3 to 6 requirement questions for a software project.
                    Each item must have: key, prompt, required (boolean), recommended (option id), and options.
                    Each option must have id, label, description. Questions must clarify product type, users,
                    core pages/features, the data to manage, and visual direction. Keep options concrete and
                    include an option id of other on every select question. The backend asks storage provider
                    and sign-in separately using available services: do not generate questions for storage,
                    database provider, backend stack or authentication and never use platform_ question keys.
                    Do not ask questions already answered clearly by the user prompt. User prompt: %s
                    """.formatted(project.initialPrompt()) + attachmentNote(project.attachments());
            LlmResult result = llm.chat(new LlmRequest(selection.provider(), selection.model(),
                    List.of(LlmMessage.system("You are a product requirements analyst."), LlmMessage.user(prompt)), List.of(), 0.0, LLM_BUDGET)
                    .withMaxTokens(properties.getLlm().getMaxOutputTokens()));
            List<QuestionDraft> parsed = parseQuestions(result.content());
            if (!parsed.isEmpty()) return parsed.stream().limit(8).toList();
        } catch (RuntimeException error) {
            log.warn("requirement_questions_fallback project={} reason={}", project.id(), error.getClass().getSimpleName());
        }
        return defaults(project.initialPrompt());
    }

    private List<QuestionDraft> parseQuestions(String content) {
        if (content == null || content.isBlank()) return List.of();
        return parseQuestionDrafts(objectMapper.readTree(stripJsonFence(content)));
    }

    private List<QuestionDraft> parseQuestionDrafts(JsonNode root) {
        if (root == null || !root.isArray()) return List.of();
        List<QuestionDraft> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : root) {
            if (result.size() >= 8) break;
            String key = node.path("key").asText("").replaceAll("[^a-zA-Z0-9_-]", "");
            String prompt = node.path("prompt").asText("").trim();
            if (key.isBlank() || key.length() > 80 || prompt.isBlank() || prompt.length() > 2_000
                    || keys.contains(key.toLowerCase(Locale.ROOT)) || !node.path("options").isArray()) continue;
            List<OptionDraft> options = new ArrayList<>();
            Set<String> optionIds = new HashSet<>();
            for (JsonNode option : node.path("options")) {
                if (options.size() >= 12) break;
                String id = option.path("id").asText("").replaceAll("[^a-zA-Z0-9_-]", "");
                if (!id.isBlank() && id.length() <= 80 && optionIds.add(id)) options.add(new OptionDraft(id,
                        Optional.ofNullable(clean(option.path("label").asText(id), 300)).orElse(id),
                        Optional.ofNullable(clean(option.path("description").asText(""), 1_000)).orElse("")));
            }
            if (options.size() >= 2) {
                keys.add(key.toLowerCase(Locale.ROOT));
                String recommended = node.path("recommended").asText("").trim();
                boolean validRecommendation = options.stream().anyMatch(option -> option.id().equals(recommended));
                result.add(new QuestionDraft(key, prompt, node.path("required").asBoolean(true),
                        validRecommendation ? recommended : options.get(0).id(), options));
            }
        }
        return result;
    }

    private static String stripJsonFence(String content) {
        return content.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }

    /** The fallback questions when the model cannot write them: the to-do set only for a to-do request. */
    private List<QuestionDraft> defaults(String prompt) {
        String request = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
        if (!request.matches("(?s).*\\b(to-?do|todos?|tasks?|checklist)\\b.*")) {
            return List.of(
                    q("audience", "Who will use it?", true, "just_me", List.of(o("just_me", "Just for me", "A personal tool or a game for yourself"), o("public", "Friends or customers", "Other people will use it, so it should be polished and self-explanatory"), o("learning_demo", "Learning/demo project", "Exploring what the product can do"), o("other", "Write your own...", "Describe who will use it and why"))),
                    q("scope", "How complete should the first version be?", true, "standard", List.of(o("core", "Simple core", "Only the main feature, done well"), o("standard", "Standard", "The main feature plus the obvious extras users expect"), o("full_featured", "Full-featured", "Everything that makes it feel like a finished product"), o("other", "Write your own...", "Describe the features you need"))),
                    q("visual_direction", "What visual style should it have?", true, "clean_minimal", List.of(o("clean_minimal", "Clean & minimal", "Light, airy, generous whitespace"), o("calm_warm", "Calm & warm", "Soothing colors, soft shapes, friendly tone"), o("bold_colorful", "Bold & colorful", "Vivid colors and big, playful elements"), o("dark_moody", "Dark & moody", "Dark background with vibrant accents"), o("other", "Write your own...", "Describe the visual style")))
            );
        }
        return List.of(
                q("audience", "Who will use this to-do app, and for what kind of tasks?", true, "just_me", List.of(o("just_me", "Just for me", "You use it alone to track daily tasks"), o("shared_accounts", "Shared with accounts", "Multiple people sign in and each manages their own tasks"), o("learning_demo", "Learning/demo project", "Exploring what the product can do"), o("other", "Write your own...", "Describe who will use it and why"))),
                q("features", "Beyond adding and completing tasks, which features matter?", true, "bare_minimum", List.of(o("bare_minimum", "Bare minimum", "Add, check off, delete - nothing else"), o("standard", "Standard", "Due dates, priorities, and categories/projects"), o("full_featured", "Full-featured", "Due dates, priorities, subtasks, search, and filters"), o("other", "Write your own...", "Describe the features you need"))),
                q("visual_direction", "What visual style should it have?", true, "dark_moody", List.of(o("clean_minimal", "Clean & minimal", "Light, airy, generous whitespace - Things/Todoist style"), o("calm_warm", "Calm & warm", "Soothing colors, soft shapes, friendly tone"), o("bold_structured", "Bold & structured", "Dense information layout, sharp edges, productivity-tool vibe"), o("dark_moody", "Dark & moody", "Dark background with vibrant accents"), o("other", "Write your own...", "Describe the visual style")))
        );
    }

    private static QuestionDraft q(String key, String prompt, boolean required, String recommended, List<OptionDraft> options) { return new QuestionDraft(key, prompt, required, recommended, options); }
    private static OptionDraft o(String id, String label, String description) { return new OptionDraft(id, label, description); }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (RuntimeException e) { throw new IllegalStateException(e); } }
    private static String clean(String value, int max) { if (value == null) return null; String v = value.trim(); return v.isBlank() ? null : v.substring(0, Math.min(max, v.length())); }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private record OptionDraft(String id, String label, String description) {}
    private record QuestionDraft(String key, String prompt, boolean required, String recommended, List<OptionDraft> options) {}
    private record Assessment(boolean clear, List<QuestionDraft> questions, boolean fallback) {
        private Assessment(boolean clear, List<QuestionDraft> questions) { this(clear, questions, false); }
    }
    /** What the model calls need from a project, read inside a transaction and used outside it. */
    /** {@code attachments}: files attached to the opening prompt (screenshots, mock-ups). */
    private record ProjectBrief(UUID id, String initialPrompt, List<String> attachments) {
        static ProjectBrief of(Project project, List<String> attachments) {
            return new ProjectBrief(project.getId(), Optional.ofNullable(project.getInitialPrompt()).orElse(""), attachments);
        }
    }
    /** Result of recording an answer: the session view when no review is due, else what the review needs. */
    private record Recorded(RequirementDtos.Session view, ProjectBrief project, String summary, UUID session, long revision,
                            com.code.pal.project.requirement.RequirementCoordinator.Claim claim) {}
    private record Interview(ProjectBrief project, UUID session, long revision,
                             com.code.pal.project.requirement.RequirementCoordinator.Claim claim, RequirementDtos.Session view) {}
}
