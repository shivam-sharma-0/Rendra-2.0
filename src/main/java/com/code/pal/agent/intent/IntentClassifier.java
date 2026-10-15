package com.code.pal.agent.intent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.llm.LlmClient;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Decides whether a user turn is small talk that deserves a quick, direct reply, or a real task
 * that needs the full explore/plan/implement/validate pipeline. Deterministic scope rules route tasks;
 * an ambiguous short message
 * receives a direct reply/clarifying question. Classification itself never invokes a model.
 */
@Service
public class IntentClassifier {

    private static final int SHORT_MESSAGE_CHARS = 60;

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
                    + "project|file|code|function|class|module|calculator|counter|timer|quiz|app|application|website|react|todo|to[- ]do)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_OR_PATH_SIGNAL = Pattern.compile(
            "[\\w-]+/[\\w./-]+|\\.[a-zA-Z0-9]{1,5}\\b");

    @org.springframework.beans.factory.annotation.Autowired
    public IntentClassifier() {}
    /** Source compatibility for existing embedders; classification no longer uses these services. */
    public IntentClassifier(LlmClient ignoredClient,AgentProperties ignoredProperties) {this();}

    public AgentIntent classify(String userRequest) {
        String trimmed = RequestScope.normalize(userRequest);
        if (trimmed.isEmpty()) return AgentIntent.CONVERSATIONAL;
        if (hasTaskSignal(trimmed)) return AgentIntent.TASK;
        if (CONVERSATIONAL_PATTERN.matcher(trimmed).matches()) return AgentIntent.CONVERSATIONAL;
        if (trimmed.length() > SHORT_MESSAGE_CHARS) return AgentIntent.TASK;
        // An unclear short request does not authorize code changes. The normal reply can clarify it
        // in one call instead of spending an intent call and then a second answer call.
        return AgentIntent.CONVERSATIONAL;
    }

    private static boolean hasTaskSignal(String text) {
        return RequestScope.action(text) || TASK_SIGNAL_PATTERN.matcher(text).find() || FILE_OR_PATH_SIGNAL.matcher(text).find()
                || text.matches("^(?:continue|start karo|shuru karo|aage badho|agla step|next step)[.! ]*$");
    }

}
