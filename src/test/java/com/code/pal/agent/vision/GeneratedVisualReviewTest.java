package com.code.pal.agent.vision;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.llm.LlmClient.Usage;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedVisualReviewTest {
    private final AgentProperties properties = new AgentProperties();
    private final LocalProject project = LocalProject.create(properties).write("index.html", "<h1>Appointments</h1>");
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeneratedVisualReview review = new GeneratedVisualReview(properties, project.files, mapper);
    private final AgentContext context = mock(AgentContext.class);

    private void capture() throws Exception {
        properties.getModels().setVision(List.of("nvidia:vision-fixture"));
        when(context.projectId()).thenReturn(project.projectId);
        when(context.runId()).thenReturn(UUID.randomUUID());
        when(context.root()).thenReturn(".");
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var captures = List.of("desktop", "mobile").stream().map(name -> {
            try {
                var destination = project.path(".codepal/browser-evidence/current/" + name + ".png");
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes.toByteArray());
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
                return Map.of("name", name, "sha256", hash);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).toList();
        review.record(context, mapper.valueToTree(Map.of("visual", Map.of("captures", captures))));
    }

    @Test
    void twoVerifiedViewsUseOneBoundedCallAndEvidenceIsConsumed() throws Exception {
        capture();
        var calls = new AtomicInteger();
        var issues = review.review(context, "Booking calendar", request -> {
            calls.incrementAndGet();
            assertEquals("vision-fixture", request.model());
            assertEquals(2, request.messages().getLast().images().size());
            assertTrue(request.messages().getFirst().content().contains("untrusted"));
            assertTrue(request.tools().isEmpty());
            assertEquals(properties.getVisualReview().getMaxOutputTokens(), request.maxTokens());
            return new LlmResult("{\"issues\":[\"Increase contrast of the secondary text\"]}", null, List.of(), new Usage(50, 10), "stop");
        });
        assertEquals(1, issues.size());
        assertEquals(1, calls.get());
        assertTrue(review.review(context, "Again", request -> { throw new AssertionError("Single use"); }).isEmpty());
    }

    @Test
    void changedSourceOrTamperedCaptureNeverCallsTheModel() throws Exception {
        capture();
        project.write("index.html", "<h1>A different revision</h1>");
        assertTrue(review.review(context, "Changed", request -> { throw new AssertionError("Stale source"); }).isEmpty());
        capture();
        Files.writeString(project.path(".codepal/browser-evidence/current/desktop.png"), "tampered");
        assertTrue(review.review(context, "Changed", request -> { throw new AssertionError("Tampered image"); }).isEmpty());
    }

    @Test
    void malformedOrTruncatedFeedbackDoesNotBecomeAnotherRepairLoop() throws Exception {
        for (String response : List.of("not json", "{\"issues\":[true]}", "{\"issues\":[\"" + "x".repeat(501) + "\"]}")) {
            capture();
            assertTrue(review.review(context, "App", request -> new LlmResult(response, null, List.of(), new Usage(1, 1), "stop")).isEmpty());
        }
        capture();
        assertTrue(review.review(context, "App", request -> new LlmResult("{\"issues\":[\"bad\"]}", null, List.of(), new Usage(1, 1), "length")).isEmpty());
    }

    @Test
    void disabledAndOtherProjectEvidenceIsNotSent() throws Exception {
        capture();
        properties.getVisualReview().setEnabled(false);
        assertTrue(review.review(context, "App", request -> { throw new AssertionError("Disabled"); }).isEmpty());
        properties.getVisualReview().setEnabled(true);
        capture();
        when(context.projectId()).thenReturn(UUID.randomUUID());
        assertTrue(review.review(context, "App", request -> { throw new AssertionError("Other project"); }).isEmpty());
    }
}
