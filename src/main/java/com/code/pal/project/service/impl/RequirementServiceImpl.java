package com.code.pal.project.service.impl;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.service.MessageService;
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
        // Without a transaction: generating questions calls the model (see {@link #tx}).
        ProjectBrief needsQuestions = tx.execute(status -> {
            Project project = projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseGet(() -> sessions.save(
                    RequirementSession.builder().projectId(projectId).sourcePrompt(Optional.ofNullable(project.getInitialPrompt()).orElse("")).build()));
            return questions.findBySessionIdOrderBySequenceAsc(session.getId()).isEmpty() ? ProjectBrief.of(project) : null;
        });
        List<QuestionDraft> drafts = needsQuestions == null ? List.of() : generate(needsQuestions);
        return tx.execute(status -> {
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            // A concurrent start may have saved its questions meanwhile; the first set wins.
            if (!drafts.isEmpty() && questions.findBySessionIdOrderBySequenceAsc(session.getId()).isEmpty()) {
                saveQuestions(session, drafts);
            }
            if (session.getStatus() == RequirementStatus.DRAFT) session.setStatus(RequirementStatus.IN_PROGRESS);
            return view(session);
        });
    }

    @Override
    public RequirementDtos.Session answer(Long userId, UUID projectId, UUID questionId, RequirementDtos.AnswerRequest request) {
        // Without a transaction: the last answer triggers a model review (see {@link #tx}).
        Recorded recorded = tx.execute(status -> {
            Project project = projects.requireCanEdit(userId, projectId);
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            RequirementQuestion question = questions.findByIdAndSessionId(questionId, session.getId()).orElseThrow(ProjectNotFoundException::new);
            String selected = clean(request.selectedOption(), 80);
            String other = clean(request.otherText(), 4_000);
            if (question.isRequired() && (selected == null || selected.isBlank()) && (other == null || other.isBlank())) {
                throw new InvalidRequirementAnswerException("This requirement question needs an answer");
            }
            question.setSelectedOption(selected);
            question.setOtherText(other);
            session.setStatus(RequirementStatus.IN_PROGRESS);
            if (!complete(session)) return new Recorded(view(session), null, null);
            return new Recorded(null, ProjectBrief.of(project), answerSummary(questions.findBySessionIdOrderBySequenceAsc(session.getId())));
        });
        if (recorded.view() != null) return recorded.view();

        Assessment assessment;
        try {
            assessment = assess(recorded.project(), recorded.summary());
        } catch (RuntimeException error) {
            // The user can still review the complete answer set when the assessment provider is unavailable.
            log.warn("Requirement assessment fell back to approval project={}: {}", projectId, error.getMessage());
            assessment = new Assessment(true, List.of());
        }
        Assessment verdict = assessment;
        return tx.execute(status -> {
            RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
            // Answers may have changed (or been approved) while the model was reviewing them.
            if (session.getStatus() != RequirementStatus.APPROVED && complete(session)) refineOrApprove(session, verdict);
            return view(session);
        });
    }

    /** Applies a review of the complete answer set, adding only genuinely missing decisions. */
    private void refineOrApprove(RequirementSession session, Assessment assessment) {
        List<RequirementQuestion> answered = questions.findBySessionIdOrderBySequenceAsc(session.getId());
        if (!assessment.clear() && !assessment.questions().isEmpty()) {
            Set<String> knownKeys = answered.stream().map(RequirementQuestion::getQuestionKey)
                    .map(key -> key.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            int remaining = Math.max(0, 8 - answered.size());
            List<QuestionDraft> additional = assessment.questions().stream()
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
                List.of(LlmMessage.system("You are a strict product requirements reviewer."), LlmMessage.user(prompt)), List.of(), 0.0, LLM_BUDGET));
        JsonNode root = objectMapper.readTree(stripJsonFence(result.content()));
        if (!root.isObject()) throw new IllegalArgumentException("Requirement assessment was not an object");
        return new Assessment(root.path("requirementsClear").asBoolean(false), parseQuestionDrafts(root.path("questions")));
    }

    @Override
    @Transactional
    public RequirementDtos.Session approve(Long userId, UUID projectId) {
        projects.requireCanEdit(userId, projectId);
        RequirementSession session = sessions.findByProjectId(projectId).orElseThrow(ProjectNotFoundException::new);
        if (session.getStatus() == RequirementStatus.APPROVED) return view(session);
        if (!complete(session)) throw new WorkflowStageException("Answer all required requirement questions first");
        session.setStatus(RequirementStatus.APPROVED);
        session.setApprovedAt(Instant.now());
        session.setApprovedBy(userId);
        List<RequirementQuestion> answered = questions.findBySessionIdOrderBySequenceAsc(session.getId());
        projects.advanceLifecycle(userId, projectId, ProjectStage.DESIGN);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        // Post first: it applies the usage limits, and a rejection rolls this whole transaction back
        // before anything is written to the workspace. The build run starts only after commit.
        messages.post(userId, projectId, new CreateMessageRequest(
                "req_" + suffix.substring(0, 27), approvedBuildPrompt(answered), ProjectType.BUILD,
                "ai_" + UUID.randomUUID().toString().replace("-", "").substring(0, 27)));
        artifacts.writeRequirements(projectId, session.getId(), answered);
        return view(session);
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
        return questions.findBySessionIdOrderBySequenceAsc(session.getId()).stream()
                .filter(RequirementQuestion::isRequired)
                .allMatch(q -> (q.getSelectedOption() != null && !q.getSelectedOption().isBlank())
                        || (q.getOtherText() != null && !q.getOtherText().isBlank()));
    }

    private RequirementDtos.Session view(RequirementSession session) {
        List<RequirementDtos.Question> result = questions.findBySessionIdOrderBySequenceAsc(session.getId()).stream().map(this::question).toList();
        return new RequirementDtos.Session(session.getId(), session.getProjectId(), session.getStatus(), result,
                result.stream().filter(RequirementDtos.Question::required).allMatch(q -> nonBlank(q.selectedOption()) || nonBlank(q.otherText())), session.getApprovedAt());
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

    private void saveQuestions(RequirementSession session, List<QuestionDraft> drafts, int sequenceOffset) {
        for (int i = 0; i < drafts.size(); i++) {
            QuestionDraft draft = drafts.get(i);
            questions.save(RequirementQuestion.builder().sessionId(session.getId()).sequence(sequenceOffset + i + 1).questionKey(draft.key())
                    .prompt(draft.prompt()).type(RequirementQuestionType.SINGLE_SELECT).required(draft.required())
                    .optionsJson(write(draft.options())).recommendedOption(draft.recommended()).build());
        }
    }

    private List<QuestionDraft> generate(ProjectBrief project) {
        try {
            AgentProperties.ModelSelection selection = properties.modelFor(ProjectType.PLAN);
            String prompt = """
                    Return only valid JSON array. Create 5 to 7 requirement questions for a software project;
                    for a to-do or task app, create exactly the four questions specified below.
                    Each item must have: key, prompt, required (boolean), recommended (option id), and options.
                    Each option must have id, label, description. Questions must clarify product type, users,
                    core pages/features, authentication, data, and visual direction. Keep options concrete and
                    include an option id of other on every select question. For a to-do or task app, use these
                    question prompts and option labels exactly: users (Just for me, Shared with accounts,
                    Learning/demo project), features (Bare minimum, Standard, Full-featured), storage (Saved
                    database (Cloud), This device only), and visual style (Clean & minimal, Calm & warm,
                    Bold & structured, Dark & moody). Add Write your own... as the final option for each.
                    Do not ask questions already answered clearly by the user prompt. User prompt: %s
                    """.formatted(project.initialPrompt());
            LlmResult result = llm.chat(new LlmRequest(selection.provider(), selection.model(),
                    List.of(LlmMessage.system("You are a product requirements analyst."), LlmMessage.user(prompt)), List.of(), 0.0, LLM_BUDGET));
            List<QuestionDraft> parsed = parseQuestions(result.content());
            if (!parsed.isEmpty()) return parsed.stream().limit(8).toList();
        } catch (RuntimeException error) {
            log.warn("Requirement question generation fell back to defaults project={}: {}", project.id(), error.getMessage());
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
        for (JsonNode node : root) {
            String key = node.path("key").asText("").replaceAll("[^a-zA-Z0-9_-]", "");
            String prompt = node.path("prompt").asText("").trim();
            if (key.isBlank() || prompt.isBlank() || !node.path("options").isArray()) continue;
            List<OptionDraft> options = new ArrayList<>();
            for (JsonNode option : node.path("options")) {
                String id = option.path("id").asText("").replaceAll("[^a-zA-Z0-9_-]", "");
                if (!id.isBlank()) options.add(new OptionDraft(id, option.path("label").asText(id), option.path("description").asText("")));
            }
            if (options.size() >= 2) {
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

    private List<QuestionDraft> defaults(String prompt) {
        return List.of(
                q("audience", "Who will use this to-do app, and for what kind of tasks?", true, "just_me", List.of(o("just_me", "Just for me", "You use it alone to track daily tasks"), o("shared_accounts", "Shared with accounts", "Multiple people sign in and each manages their own tasks"), o("learning_demo", "Learning/demo project", "Exploring what the product can do"), o("other", "Write your own...", "Describe who will use it and why"))),
                q("features", "Beyond adding and completing tasks, which features matter?", true, "bare_minimum", List.of(o("bare_minimum", "Bare minimum", "Add, check off, delete - nothing else"), o("standard", "Standard", "Due dates, priorities, and categories/projects"), o("full_featured", "Full-featured", "Due dates, priorities, subtasks, search, and filters"), o("other", "Write your own...", "Describe the features you need"))),
                q("storage", "Where should your tasks be saved?", true, "saved_database", List.of(o("saved_database", "Saved database (Cloud)", "Tasks persist across devices and browser sessions; supports logins later"), o("device_only", "This device only", "Lives in the browser only; simpler, no account"), o("other", "Write your own...", "Describe where the data should live"))),
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
    private record Assessment(boolean clear, List<QuestionDraft> questions) {}
    /** What the model calls need from a project, read inside a transaction and used outside it. */
    private record ProjectBrief(UUID id, String initialPrompt) {
        static ProjectBrief of(Project project) {
            return new ProjectBrief(project.getId(), Optional.ofNullable(project.getInitialPrompt()).orElse(""));
        }
    }
    /** Result of recording an answer: the session view when no review is due, else what the review needs. */
    private record Recorded(RequirementDtos.Session view, ProjectBrief project, String summary) {}
}
