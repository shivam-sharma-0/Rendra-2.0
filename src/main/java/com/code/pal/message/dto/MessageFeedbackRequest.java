package com.code.pal.message.dto;

import com.code.pal.message.domain.MessageFeedback;

/** {@code feedback} is null to clear a previously set reaction. */
public record MessageFeedbackRequest(MessageFeedback feedback) {
}
