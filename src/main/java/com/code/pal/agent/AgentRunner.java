package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import com.code.pal.agent.llm.LlmClient.ToolSpec;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The agentic loop: given a run, build context, call the LLM with the mode's tools, execute any
 * tool calls, feed results back, and repeat until the model returns a final answer (or the
 * iteration cap is hit). Progress streams to the project room via {@link AgentEvents}; the assistant
 * message is filled in {@code messages}. Not transactional — it spans slow LLM calls, so each DB
 * write is its own short transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunner {

    private static final int HISTORY_LIMIT = 20;
    private static final int TOOL_RESULT_MAX = 12_000; // truncate large tool output before feeding back

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentEvents events;
    private final AgentProperties props;
    private final AgentRunRepository runRepository;
    private final MessageRepository messageRepository;
    private final RealtimeService realtime;
    private final WorkspaceStorage workspace;
    private final ObjectMapper objectMapper;

    /** Execute a run end to end. Any failure is recorded on the run + assistant message. */
    public void run(AgentRun run) {
        try {
            setStatus(run, RunStatus.RUNNING);
            updateAssistant(run, "", MessageStatus.STREAMING);

            Path ws = workspace.ensure(run.getProjectId());
            AgentContext ctx = new AgentContext(run.getProjectId(), run.getUserId(), run.getMode(), ws);

            List<AgentTool> available = toolsForMode(run.getMode());
            List<ToolSpec> specs = available.stream()
                    .map(t -> new ToolSpec(t.name(), t.description(), t.parametersSchema())).toList();

            List<LlmMessage> convo = new ArrayList<>();
            convo.add(LlmMessage.system(systemPrompt(run.getMode(), ws)));
            convo.addAll(loadHistory(run));

            for (int i = 0; i < props.getMaxIterations(); i++) {
                LlmResult res = llm.chat(new LlmRequest(
                        props.modelFor(run.getMode()), convo, specs, props.getLlm().getTemperature()));
                accountTokens(run, res);

                if (!res.hasToolCalls()) {
                    updateAssistant(run, res.content() == null ? "" : res.content(), MessageStatus.COMPLETE);
                    setStatus(run, RunStatus.DONE);
                    return;
                }

                // Model may narrate before calling tools — surface it as a thought.
                if (res.content() != null && !res.content().isBlank()) {
                    events.emit(run, AgentStepType.THINKING, res.content(), null);
                }
                convo.add(LlmMessage.assistantToolCalls(res.toolCalls()));

                for (ToolCall call : res.toolCalls()) {
                    String result = executeTool(available, call, ctx, run);
                    convo.add(LlmMessage.toolResult(call.id(), result));
                }
            }

            // Hit the iteration cap without a final answer.
            updateAssistant(run, "Stopped after reaching the step limit. Please refine your request.", MessageStatus.COMPLETE);
            setStatus(run, RunStatus.DONE);

        } catch (Exception e) {
            log.warn("Agent run {} failed: {}", run.getId(), e.getMessage());
            run.setError(e.getMessage());
            setStatus(run, RunStatus.FAILED);
            updateAssistant(run, "Something went wrong while running the agent.", MessageStatus.FAILED);
        }
    }

    /* ── Tool execution ───────────────────────────────────────────────────────── */

    private String executeTool(List<AgentTool> available, ToolCall call, AgentContext ctx, AgentRun run) {
        Map<String, Object> args = parseArgs(call.arguments());
        events.emit(run, AgentStepType.TOOL_CALL, call.name(), args);

        AgentTool tool = available.stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
        String result;
        if (tool == null) {
            result = "Error: unknown tool '" + call.name() + "'";
        } else {
            try {
                result = tool.execute(args, ctx);
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
        }
        if (result != null && result.length() > TOOL_RESULT_MAX) {
            result = result.substring(0, TOOL_RESULT_MAX) + "\n…[truncated]";
        }
        events.emit(run, AgentStepType.TOOL_RESULT, call.name(), Map.of("result", result == null ? "" : result));
        return result == null ? "" : result;
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /* ── Context building ─────────────────────────────────────────────────────── */

    private List<AgentTool> toolsForMode(ProjectType mode) {
        boolean allowMutating = mode == ProjectType.BUILD;
        return tools.all().stream().filter(t -> allowMutating || !t.mutating()).toList();
    }

    private List<LlmMessage> loadHistory(AgentRun run) {
        var page = messageRepository.findByProjectId(
                run.getProjectId(), PageRequest.of(0, HISTORY_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")));
        // Fetched newest-first for the cap; replay oldest-first.
        List<Message> ordered = new ArrayList<>(page.getContent());
        ordered.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

        List<LlmMessage> out = new ArrayList<>();
        for (Message m : ordered) {
            if (m.getId().equals(run.getMessageId())) continue;          // skip the placeholder we're filling
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            switch (m.getRole()) {
                case USER -> out.add(LlmMessage.user(m.getContent()));
                case ASSISTANT -> out.add(LlmMessage.assistant(m.getContent()));
                case SYSTEM -> out.add(LlmMessage.system(m.getContent()));
            }
        }
        return out;
    }

    private String systemPrompt(ProjectType mode, Path ws) {
        String base = """
                You are Rendra, an expert AI coding agent working inside a user's project.
                Use the provided tools to inspect and change files. Paths are relative to the project root.
                Be precise and concise. Prefer reading a file before editing it.
                """;
        String modeRule = switch (mode) {
            case BUILD -> "Mode: BUILD. Make the requested code changes using write_file. End with a short summary of what you changed.";
            case CHAT -> "Mode: CHAT. Answer and explain. You may read/list files for context, but do NOT modify files.";
            case PLAN -> "Mode: PLAN. Produce a clear, step-by-step implementation plan. Do NOT modify files.";
        };
        return base + modeRule + "\n\nProject files (top level):\n" + repoMap(ws);
    }

    private String repoMap(Path ws) {
        try (Stream<Path> list = Files.list(ws)) {
            List<String> names = list
                    .map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
                    .sorted().limit(50).toList();
            return names.isEmpty() ? "(empty project)" : String.join("\n", names);
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    /* ── Persistence helpers (each its own short tx via repository) ───────────── */

    private void setStatus(AgentRun run, RunStatus status) {
        run.setStatus(status);
        runRepository.save(run);
    }

    private void accountTokens(AgentRun run, LlmResult res) {
        if (res.usage() == null) return;
        run.setPromptTokens(run.getPromptTokens() + res.usage().promptTokens());
        run.setCompletionTokens(run.getCompletionTokens() + res.usage().completionTokens());
        runRepository.save(run);
    }

    private void updateAssistant(AgentRun run, String content, MessageStatus status) {
        messageRepository.findById(run.getMessageId()).ifPresent(m -> {
            m.setContent(content);
            m.setStatus(status);
            Message saved = messageRepository.save(m);
            realtime.sendToProject(run.getProjectId(), RealtimeMessage.of("MESSAGE_UPSERT", MessageResponse.from(saved)));
        });
    }
}
