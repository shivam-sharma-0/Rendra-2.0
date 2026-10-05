package com.code.pal.agent.preview;

import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tells everyone viewing the project when its preview starts building, is updated, or fails to build. */
@Component
@RequiredArgsConstructor
public class RealtimePreviewListener implements SiteBuildService.PreviewListener {
    public static final String TYPE = "PREVIEW_STATUS";

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
}
