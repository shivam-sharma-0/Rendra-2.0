package com.code.pal.agent.web;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Stores user attachments inside the persistent project sandbox, never on the backend host. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/attachments")
public class ProjectAttachmentController {
    private final ProjectService projects;
    private final SandboxPort sandbox;
    private final AgentProperties properties;

    @PostMapping(consumes = "multipart/form-data")
    public List<AttachmentResponse> upload(@AuthenticationPrincipal AuthPrincipal principal,
                                           @org.springframework.web.bind.annotation.PathVariable UUID projectId,
                                           @RequestPart("files") List<MultipartFile> files) {
        projects.requireCanEdit(principal.userId(), projectId);
        if (!properties.getSandbox().isRequired()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Project sandbox is not enabled");
        }
        return files.stream().map(file -> store(projectId, file)).toList();
    }

    private AttachmentResponse store(UUID projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment is empty");
        if (file.getSize() > 10_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment exceeds 10 MB");
        String original = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
        String safeName = original.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) safeName = "attachment";
        if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
        String path = "uploads/" + UUID.randomUUID() + "-" + safeName;
        try {
            sandbox.writeBytes(projectId, path, file.getBytes());
            return new AttachmentResponse(path, original, file.getSize(), file.getContentType());
        } catch (IOException | RuntimeException error) {
            // writeBytes validates the quota after writing; remove the file if that validation fails.
            try {
                sandbox.delete(projectId, path);
            } catch (RuntimeException ignored) {
                // Preserve the original upload error; cleanup is best effort.
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not store attachment", error);
        }
    }

    public record AttachmentResponse(String path, String originalName, long size, String contentType) {}
}
