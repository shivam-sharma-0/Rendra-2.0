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
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.capability.DecisionDefaults;
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
    /** Section 7 target: at most two questions, none when the request is clear. */
    private static final int MAX_QUESTIONS = 2;
    /** Enough for two questions with options; the old cap reserved a full generation budget. */
    private static final int QUESTION_OUTPUT_TOKENS = 1_500;
    /** Two questions plus a bounded spec draft, only when the spec-driven build path is enabled. */
    private static final int SPEC_OUTPUT_TOKENS = 4_000;
    private static final java.time.Duration SPEC_LLM_BUDGET = java.time.Duration.ofSeconds(45);
    /** A spec draft larger than this cannot fit the approved spec envelope, so it is not kept. */
    private static final int MAX_SPEC_DRAFT_CHARS = 24_000;

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
            Drafted generated;
            if (interview.claim() != null && interview.claim().fallback()) generated = new Drafted(defaults(interview.project().initialPrompt()), null);
            else try (LlmCallContext.Scope call = LlmCallContext.bind(new LlmCallContext(userId, projectId, null, CallPurpose.REQUIREMENTS, false))) {
                generated = generate(interview.project());
            }
            // A modern default look is used (or the attached images decide it), so style is never asked.
            List<QuestionDraft> drafts = generated.questions().stream().filter(draft -> !isAboutTheLook(draft)).toList();
            result = tx.execute(status -> {
                lockRequirements(projectId);
                projects.requireCanEdit(userId, projectId);
                RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
                boolean current = session.getId().equals(interview.session()) && session.getAnswerRevision() == interview.revision();
                boolean owned = coordinator == null || coordinator.finish(interview.session(), interview.revision(), "QUESTIONS", interview.claim(), current ? "COMPLETE" : "STALE");
                if (current && owned && questions.findBySessionIdOrderBySequenceAsc(session.getId()).isEmpty()) {
                    session.setSpecDraftJson(generated.spec());
                    saveQuestions(session, capabilityQuestions(projectId, drafts));
                    ensureCapabilityQuestions(session);
                    if (drafts.isEmpty()) {
                        preselectCapabilities(session, interview.project().initialPrompt());
                    }
                    if (complete(session)) {
                        session.setStatus(RequirementStatus.APPROVAL_REQUIRED);
                    }
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
        return tx.execute(status -> {
            lockRequirements(projectId);
            projects.requireCanEdit(userId, projectId);
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
            if (changed) {
                preselectAfterProductAnswers(session);
            }
            if (!complete(session) || session.getStatus() == RequirementStatus.APPROVED
                    || session.getStatus() == RequirementStatus.APPROVAL_REQUIRED) {
                return view(session);
            }
            // Complete answers go straight to approval: a second model review of the answers is not needed.
            session.setStatus(RequirementStatus.APPROVAL_REQUIRED);
            return view(session);
        });
    }

    private void lockRequirements(UUID project) { if (coordinator != null) coordinator.lock(project); }

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
                        new com.code.pal.project.requirement.RequirementProjection.Payload(write(manifest), brief,
                                approvedSpecDraft(session, answered))));
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

    /**
     * Once every product question is answered, storage and sign-in are inferred from the request plus those
     * answers (answer labels only, never the model-written question text).
     */
    private void preselectAfterProductAnswers(RequirementSession session) {
        List<RequirementQuestion> product = questions.findBySessionIdOrderBySequenceAsc(session.getId()).stream()
                .filter(question -> !question.getQuestionKey().startsWith("platform_")).toList();
        if (product.stream().filter(RequirementQuestion::isRequired).anyMatch(question -> answerLabel(question) == null)) {
            return;
        }
        StringBuilder context = new StringBuilder(Optional.ofNullable(session.getSourcePrompt()).orElse(""));
        for (RequirementQuestion question : product) {
            String answer = answerLabel(question);
            if (answer != null) {
                context.append('\n').append(answer);
            }
        }
        preselectCapabilities(session, context.toString());
    }

    /** Answers storage and sign-in from the request when it already says enough; the user can still change them. */
    private void preselectCapabilities(RequirementSession session, String prompt) {
        if (buildDecisions == null) {
            return;
        }
        var inferred = DecisionDefaults.infer(prompt,
                buildDecisions.available(session.getProjectId()).supabaseConnected());
        for (RequirementQuestion question : questions.findBySessionIdOrderBySequenceAsc(session.getId())) {
            if (question.getSelectedOption() != null) {
                continue;
            }
            String choice = question.getQuestionKey().equals(BuildDecisions.STORAGE_KEY) && inferred.storage() != null
                    ? inferred.storage().name()
                    : question.getQuestionKey().equals(BuildDecisions.AUTH_KEY) && inferred.auth() != null
                            ? inferred.auth().name()
                            : null;
            if (choice != null && optionExists(question, choice)) {
                question.setSelectedOption(choice);
            }
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

    /**
     * One call returns the blocking questions and, when the spec-driven build path is enabled, a spec draft
     * that assumes each recommended answer.
     */
    private Drafted generate(ProjectBrief project) {
        try {
            AgentProperties.ModelSelection selection = properties.modelFor(ProjectType.PLAN);
            boolean withSpec = properties.isStructuredContracts();
            String prompt = """
                    Return only one JSON object {"questions": [...]%s} for this software project.
                    questions: 0 to 2 blocking questions, only what a sensible production default cannot decide (the
                    core purpose or main features when unclear, who uses it when that changes the product); [] when
                    the request is clear. Never ask about visual style, storage, database, backend or sign-in, and never
                    use platform_ keys. Each question has key, prompt, required (boolean), recommended (option id) and
                    options (id, label, description), including an option with id "other".
                    %s
                    User prompt: %s
                    """.formatted(withSpec ? ", \"spec\": {...}" : "", withSpec ? SPEC_INSTRUCTIONS : "",
                    project.initialPrompt()) + attachmentNote(project.attachments());
            LlmResult result = llm.chat(new LlmRequest(selection.provider(), selection.model(),
                    List.of(LlmMessage.system("You are a product requirements analyst."), LlmMessage.user(prompt)), List.of(), 0.0,
                    withSpec ? SPEC_LLM_BUDGET : LLM_BUDGET).withMaxTokens(withSpec ? SPEC_OUTPUT_TOKENS : QUESTION_OUTPUT_TOKENS));
            String content = result.content() == null ? "" : stripJsonFence(result.content()).strip();
            if (content.equals("[]")) return new Drafted(List.of(), null);
            JsonNode root = content.isBlank() ? null : objectMapper.readTree(content);
            if (root != null && root.isObject() && root.path("questions").isArray()) {
                return new Drafted(limited(parseQuestionDrafts(root.path("questions"))), withSpec ? specDraft(root.path("spec")) : null);
            }
            List<QuestionDraft> parsed = parseQuestionDrafts(root);
            if (!parsed.isEmpty()) return new Drafted(limited(parsed), null);
        } catch (RuntimeException error) {
            log.warn("requirement_questions_fallback project={} reason={}", project.id(), error.getClass().getSimpleName());
        }
        return new Drafted(defaults(project.initialPrompt()), null);
    }

    private static final String SPEC_INSTRUCTIONS = """
                    spec: what to build, assuming each question's recommended option. Fields: title, language (e.g. "en"),
                    goal; pages [{id, path ("/" first), title, featureIds}]; entities [{id, fields [{name, kind:
                    STRING|NUMBER|BOOLEAN|DATE, required}], ownership: USER|SHARED}] only for data the user saves;
                    features [{id, kind, description, pageIds, entityIds}] with kind CONTENT, CONTACT_LINK, NAV_ANCHOR,
                    CALCULATOR, COUNTER, LOCAL_CRUD or a short custom UPPER_CASE kind; theme {background, foreground,
                    accent, muted (hex colors), radius, spacing (like "8px"), font: system|serif|mono}; acceptance
                    [{id, featureId, kind: "BROWSER", steps [{kind: CLICK|FILL|SELECT|ASSERT_TEXT|ASSERT_COUNT|
                    ASSERT_ROUTE|RELOAD, selector, value}], expected}] with at least one scenario per feature;
                    constraints {responsive: true, accessibility: true, exclusions: []}. Ids are letters, digits and _.
                    Page featureIds and feature pageIds must agree.""";

    private static List<QuestionDraft> limited(List<QuestionDraft> parsed) {
        return parsed.stream().filter(draft -> !isAboutTheLook(draft)).limit(MAX_QUESTIONS).toList();
    }

    /** Kept as data only; the approved spec envelope validates every field before anything uses it. */
    private String specDraft(JsonNode spec) {
        if (spec == null || !spec.isObject()) return null;
        String json = write(spec);
        return json.length() > MAX_SPEC_DRAFT_CHARS ? null : json;
    }

    /**
     * The draft assumed each recommended answer, so it is approved only when the user kept them all;
     * otherwise the build plans from the answers without a stale spec.
     */
    private String approvedSpecDraft(RequirementSession session, List<RequirementQuestion> answered) {
        if (session.getSpecDraftJson() == null) return null;
        boolean keptRecommendations = answered.stream().filter(question -> !question.getQuestionKey().startsWith("platform_"))
                .allMatch(question -> (question.getOtherText() == null || question.getOtherText().isBlank())
                        && Objects.equals(question.getSelectedOption(), question.getRecommendedOption()));
        return keptRecommendations ? session.getSpecDraftJson() : null;
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
    private record Drafted(List<QuestionDraft> questions, String spec) {}
    private record QuestionDraft(String key, String prompt, boolean required, String recommended, List<OptionDraft> options) {}
    /** What the model calls need from a project, read inside a transaction and used outside it. */
    /** {@code attachments}: files attached to the opening prompt (screenshots, mock-ups). */
    private record ProjectBrief(UUID id, String initialPrompt, List<String> attachments) {
        static ProjectBrief of(Project project, List<String> attachments) {
            return new ProjectBrief(project.getId(), Optional.ofNullable(project.getInitialPrompt()).orElse(""), attachments);
        }
    }
    /** Result of recording an answer: the session view when no review is due, else what the review needs. */
    private record Interview(ProjectBrief project, UUID session, long revision,
                             com.code.pal.project.requirement.RequirementCoordinator.Claim claim, RequirementDtos.Session view) {}
}
