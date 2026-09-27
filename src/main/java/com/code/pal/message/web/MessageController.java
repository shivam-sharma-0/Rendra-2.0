package com.code.pal.message.web;

import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.dto.MessageFeedbackRequest;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.service.MessageService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.shared.web.ApiConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** A project's conversation. Nested under the project; access is via its workspace membership. */
@RestController
@RequestMapping("/api/projects/{projectId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<MessageResponse> post(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID projectId,
                                      @Valid @RequestBody CreateMessageRequest request) {
        return messageService.post(principal.userId(), projectId, request);
    }

    /** Conversation oldest-first — sorted by createdAt, then seq to break a tie between a user turn
     * and its assistant placeholder created in the same instant (see {@link com.code.pal.message.MessageSequence}). */
    @GetMapping
    public Page<MessageResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID projectId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), ApiConstants.MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("seq")));
        return messageService.list(principal.userId(), projectId, pageable);
    }

    @PutMapping("/{messageId}/feedback")
    public MessageResponse setFeedback(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID projectId,
                                       @PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        return messageService.setFeedback(principal.userId(), projectId, messageId, request.feedback());
    }
}
