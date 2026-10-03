package com.code.pal.agent.intent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.project.domain.ProjectType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a user turn is small talk that deserves a quick, direct reply, or a real task
 * that needs the full explore/plan/implement/validate pipeline. Cheap regex heuristics resolve the
 * overwhelming majority of turns instantly and for free; only a genuinely ambiguous short message
 * pays for one extra classification call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifier {

    private static final int SHORT_MESSAGE_CHARS = 60;
    /** A one-word answer; past this, the reply waits on a slow provider for nothing, so assume TASK. */
    private static final java.time.Duration CLASSIFICATION_BUDGET = java.time.Duration.ofSeconds(10);

    private static final Pattern CONVERSATIONAL_PATTERN = Pattern.compile(
            "^(hi+|hey+|hello+|yo|sup|namaste|namaskar|"
                    + "good\\s?(morning|evening|afternoon|night)|"
                    + "how\\s+are\\s+you|kaise\\s+ho|kya\\s+haal\\s+hai|sab\\s+badhiya|"
                    + "thanks?( you)?|thank\\s+you|shukriya|dhanyavaad|"
                    + "ok(ay)?|cool|nice|great|awesome|perfect|got\\s+it|sounds\\s+good|"
                    + "bye|goodbye|see\\s+ya|test|testing|ping|yes|no|sure|alright)[\\s!.?]*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TASK_SIGNAL_PATTERN = Pattern.compile(
            "\\b(build|create|add|implement|fix|update|change|remove|delete|refactor|generate|"
                    + "design|integrate|connect|deploy|migrate|optimi[sz]e|rename|move|make|page|component|"
                    + "endpoint|api|route|database|schema|table|column|login|signup|auth|form|button|"
                    + "feature|bug|error|crash|test\\s|validation|dashboard|screen|ui|style|css|layout|"
                    + "project|file|code|function|class|module)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_OR_PATH_SIGNAL = Pattern.compile(
            "[\\w-]+/[\\w./-]+|\\.[a-zA-Z0-9]{1,5}\\b");

    private final LlmClient llm;
    private final AgentProperties props;

    public AgentIntent classify(String userRequest) {
        String trimmed = userRequest == null ? "" : userRequest.trim();
        if (trimmed.isEmpty()) return AgentIntent.CONVERSATIONAL;
        if (hasTaskSignal(trimmed)) return AgentIntent.TASK;
        if (CONVERSATIONAL_PATTERN.matcher(trimmed).matches()) return AgentIntent.CONVERSATIONAL;
        if (trimmed.length() > SHORT_MESSAGE_CHARS) return AgentIntent.TASK;
        return classifyWithModel(trimmed);
    }

    private static boolean hasTaskSignal(String text) {
        return TASK_SIGNAL_PATTERN.matcher(text).find() || FILE_OR_PATH_SIGNAL.matcher(text).find();
    }

    /** Fallback for short, ambiguous messages the heuristics can't confidently resolve either way. */
    private AgentIntent classifyWithModel(String userRequest) {
        try {
            String system = PromptLoader.load(Prompt.INTENT_CLASSIFIER);
            AgentProperties.ModelSelection selection = props.modelFor(ProjectType.CHAT);
            LlmResult result = llm.chat(new LlmRequest(selection.provider(), selection.model(),
                    List.of(LlmMessage.system(system), LlmMessage.user(userRequest)), List.of(), 0.0,
                    CLASSIFICATION_BUDGET));
            String answer = result.content() == null ? "" : result.content().trim().toUpperCase(Locale.ROOT);
            return answer.startsWith("CONVERSATIONAL") ? AgentIntent.CONVERSATIONAL : AgentIntent.TASK;
        } catch (RuntimeException e) {
            log.debug("Intent classification call failed, defaulting to TASK: {}", e.getMessage());
            return AgentIntent.TASK;
        }
    }
}
