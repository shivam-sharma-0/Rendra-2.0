package com.code.pal.agent.config;

import com.code.pal.project.domain.ProjectType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent configuration ({@code app.agent.*}). Kept provider-agnostic: the LLM block is any
 * OpenAI-compatible endpoint (Groq today, OpenRouter/OpenAI later) — only base-url/key/models change.
 */
@Component
@ConfigurationProperties(prefix = "app.agent")
@Getter
@Setter
public class AgentProperties {

    private final Llm llm = new Llm();
    private final Models models = new Models();

    /** Root directory holding one sub-folder per project's code. */
    private String workspaceBasePath = "./workspaces";

    /** Hard cap on LLM↔tool iterations in a single run (runaway-loop guard). */
    private int maxIterations = 25;

    /** Wall-clock budget for a single run. */
    private long runTimeoutMs = 300_000;

    /** Max concurrent runs on this instance (worker threads) — global backpressure. */
    private int workerConcurrency = 2;

    @Getter
    @Setter
    public static class Llm {
        /** OpenAI-compatible base URL (e.g. https://api.groq.com/openai). */
        private String baseUrl;
        /** Secret API key — supplied via env, never committed. */
        private String apiKey;
        private double temperature = 0.2;
    }

    @Getter
    @Setter
    public static class Models {
        private String build;
        private String chat;
        private String plan;
    }

    /** Model id for a given working mode. */
    public String modelFor(ProjectType mode) {
        return switch (mode) {
            case CHAT -> models.getChat();
            case PLAN -> models.getPlan();
            case BUILD -> models.getBuild();
        };
    }
}
