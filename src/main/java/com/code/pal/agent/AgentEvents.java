package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Records a run's timeline step (persisted for reload/replay) and broadcasts it live to the
 * project room. Each step gets a monotonic {@code seq} so clients can order and dedupe replayed
 * events. This is the single place the agent surfaces progress (thoughts, tool calls, results).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEvents {

    /** Retries if two emits race for the same seq; the unique constraint makes only one win per attempt. */
    private static final int MAX_SEQ_ATTEMPTS = 5;

    private final AgentStepWriter writer;
    private final RealtimeService realtime;
    private final ObjectMapper objectMapper;

    /** Persist a step (own transaction, conflict-retried) then broadcast it once committed. */
    public void emit(AgentRun run, AgentStepType type, String content, Object data) {
        String dataJson = serialize(data);

        int seq = -1;
        for (int attempt = 0; attempt < MAX_SEQ_ATTEMPTS; attempt++) {
            try {
                // Each attempt runs in its own transaction and flushes, so a (run_id, seq) collision
                // surfaces here and we simply claim the next sequence instead of corrupting the timeline.
                seq = writer.appendOnce(run.getId(), type, content, dataJson);
                break;
            } catch (DataIntegrityViolationException conflict) {
                if (attempt == MAX_SEQ_ATTEMPTS - 1) {
                    log.warn("Gave up persisting step for run {} after {} seq conflicts", run.getId(), MAX_SEQ_ATTEMPTS);
                    return; // never let a timeline write break the run
                }
            }
        }

        // Broadcast only after the step is committed, so clients never see a step a rollback discards.
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", run.getId().toString());
        payload.put("seq", seq);
        payload.put("content", content);
        payload.put("data", data);
        realtime.sendToProject(run.getProjectId(), RealtimeMessage.of(type.name(), payload));
    }

    private String serialize(Object data) {
        if (data == null) return null;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (RuntimeException e) {
            log.debug("Step data serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
