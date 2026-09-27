package com.code.pal.notification.web;

import com.code.pal.notification.dto.NotificationResponse;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The signed-in user's notification feed. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return notifications.list(principal.userId());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthPrincipal principal) {
        return Map.of("count", notifications.unreadCount(principal.userId()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        notifications.markRead(principal.userId(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal AuthPrincipal principal) {
        notifications.markAllRead(principal.userId());
    }
}
