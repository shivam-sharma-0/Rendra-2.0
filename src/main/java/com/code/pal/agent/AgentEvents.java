package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStep;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.repository.AgentStepRepository;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final AgentStepRepository stepRepository;
    private final RealtimeService realtime;
    private final ObjectMapper objectMapper;

    /** Persist a step and broadcast it to the project room. */
    @Transactional
    public void emit(AgentRun run, AgentStepType type, String content, Object data) {
        int seq = (int) stepRepository.countByRunId(run.getId());

        String dataJson = null;
        if (data != null) {
            try {
                dataJson = objectMapper.writeValueAsString(data);
            } catch (RuntimeException e) {
                log.debug("Step data serialize failed: {}", e.getMessage());
            }
        }

        stepRepository.save(AgentStep.builder()
                .runId(run.getId())
                .seq(seq)
                .type(type)
                .content(content)
                .data(dataJson)
                .build());

        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", run.getId().toString());
        payload.put("seq", seq);
        payload.put("content", content);
        payload.put("data", data);
        realtime.sendToProject(run.getProjectId(), RealtimeMessage.of(type.name(), payload));
    }
}
