package com.code.pal.agent.preview;

import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells everyone viewing the project when its preview starts building, is updated, or fails to build
 * ({@code PREVIEW_STATUS}), and when a running build's draft changes or ends ({@code PREVIEW_DRAFT}).
 */
@Component
@RequiredArgsConstructor
public class RealtimePreviewListener implements SiteBuildService.PreviewListener, DraftPreviewService.DraftListener {
    public static final String TYPE = "PREVIEW_STATUS";
    public static final String DRAFT_TYPE = "PREVIEW_DRAFT";

    private final RealtimeService realtime;

    @Override
    public void changed(UUID projectId, SiteBuildService.PreviewStatus status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        payload.put("state", status.state());
        payload.put("version", status.version());
        payload.put("error", status.error());
        realtime.sendToProject(projectId, RealtimeMessage.of(TYPE, payload));
    }

    @Override
    public void drafted(UUID projectId, UUID runId, String version) {
        realtime.sendToProject(projectId, RealtimeMessage.of(DRAFT_TYPE,
                Map.of("projectId", projectId, "runId", runId, "version", version, "active", true)));
    }

    @Override
    public void ended(UUID projectId, UUID runId) {
        realtime.sendToProject(projectId, RealtimeMessage.of(DRAFT_TYPE,
                Map.of("projectId", projectId, "runId", runId, "active", false)));
    }
}
