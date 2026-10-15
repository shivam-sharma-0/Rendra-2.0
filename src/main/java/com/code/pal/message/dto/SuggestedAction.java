package com.code.pal.message.dto;

import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.project.domain.ProjectType;

import java.util.List;

/** Explicit server-persisted offer; clients cannot invent build authority from reply text. */
public record SuggestedAction(String label, ProjectType mode, String message, List<String> attachments,
                              String proposalId) {
    static SuggestedAction from(Message message) {
        if (message.getRole() != MessageRole.ASSISTANT || message.getStatus() != MessageStatus.COMPLETE
                || message.getSuggestedBuildText() == null || message.getSuggestedBuildText().isBlank()
                || message.getProposalConsumedBy() != null) {
            return null;
        }
        return new SuggestedAction("Build this", ProjectType.BUILD, message.getSuggestedBuildText(),
                message.attachmentPaths(), message.getId());
    }
}
