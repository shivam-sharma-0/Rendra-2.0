package com.code.pal.agent.web;

import com.code.pal.agent.dto.DailyUsageResponse;
import com.code.pal.agent.usage.AgentUsagePolicy;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's AI usage, shown as the usage meter in the app shell. */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final AgentUsagePolicy usage;

    @GetMapping("/daily")
    public DailyUsageResponse daily(@AuthenticationPrincipal AuthPrincipal principal) {
        return DailyUsageResponse.from(usage.dailyUsage(principal.userId()));
    }
}
