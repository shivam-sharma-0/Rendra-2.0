package com.code.pal.agent.vision;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VisionServiceTest {
    private final LocalProject project = LocalProject.create();
    private final List<String> modelsCalled = new ArrayList<>();
    private final List<List<String>> imagesSent = new ArrayList<>();

    @Test
    void largeImagesAreScaledAndReencodedUnderTheInlineLimit() throws Exception {
        byte[] photo = noisyPng(3000, 2000);   // random noise compresses badly: the worst case
        assertTrue(photo.length > 1_000_000);

        String dataUrl = VisionService.dataUrl(photo);

        assertNotNull(dataUrl);
        assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"));
        String encoded = dataUrl.substring("data:image/jpeg;base64,".length());
        assertTrue(encoded.length() <= VisionService.MAX_ENCODED, "encoded " + encoded.length());
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        assertTrue(Math.max(sent.getWidth(), sent.getHeight()) <= VisionService.MAX_SIDE);
        assertEquals(1.5, (double) sent.getWidth() / sent.getHeight(), 0.02, "aspect ratio kept");
    }

    @Test
    void nonImagesAreNotSentToTheVisionModel() {
        assertNull(VisionService.dataUrl("just some text".getBytes()));
    }

    @Test
    void theNextModelIsTriedWhenOneFailsAndTheBriefIsLabelledUntrusted() throws Exception {
        Files.createDirectories(project.path("uploads"));
        Files.write(project.path("uploads/a-design.png"), noisyPng(200, 120));
        project.write("uploads/b-notes.txt", "plain text");
        AgentProperties properties = new AgentProperties();
        properties.getModels().setVision(List.of("nvidia:broken-vision", "nvidia:working-vision"));
        VisionService vision = new VisionService(model(), properties, project.files);

        List<VisionService.Brief> briefs = vision.describe(project.projectId, List.of("uploads/a-design.png", "uploads/b-notes.txt"), "make it");

        assertEquals(List.of("broken-vision", "working-vision"), modelsCalled);
        assertEquals("A header and two cards.", briefs.get(0).brief());
        assertNull(briefs.get(1).brief(), "text files are left to readFile");
        assertTrue(imagesSent.getFirst().getFirst().startsWith("data:image/jpeg;base64,"));
        String context = VisionService.asContext(briefs);
        assertTrue(context.contains("untrusted data") && context.contains("<attachment path=\"uploads/a-design.png\">"), context);
    }

    @Test
    void aQuotaRefusalOrAStopDoesNotMarkTheModelUnavailable() throws Exception {
        Files.createDirectories(project.path("uploads"));
        Files.write(project.path("uploads/a.png"), noisyPng(200, 120));
        AgentProperties properties = new AgentProperties();
        properties.getModels().setVision(List.of("nvidia:only-vision"));
        AgentProperties.ModelSelection only = properties.visionModels().getFirst();

        VisionService refused = new VisionService((request, onDelta) -> {
            throw new com.code.pal.agent.llm.LlmQuotaExceededException("Not enough of today's AI quota is left for this request.");
        }, properties, project.files);
        VisionService.Brief brief = refused.describe(project.projectId, List.of("uploads/a.png"), "make it").getFirst();
        assertNull(brief.brief());
        assertTrue(properties.available(only), "quota is not the model's fault");

        VisionService stopped = new VisionService((request, onDelta) -> { throw new com.code.pal.agent.llm.LlmCancelledException(); },
                properties, project.files);
        assertThrows(com.code.pal.agent.llm.LlmCancelledException.class,
                () -> stopped.describe(project.projectId, List.of("uploads/a.png"), "make it"));
        assertTrue(properties.available(only), "a stopped run is not the model's fault");
    }

    private LlmClient model() {
        return (request, onDelta) -> {
            modelsCalled.add(request.model());
            imagesSent.add(request.messages().getFirst().images());
            if (request.model().equals("broken-vision")) throw new IllegalStateException("HTTP 503");
            return new LlmClient.LlmResult("A header and two cards.", null, List.of(), new LlmClient.Usage(1, 1), "stop");
        };
    }

    private static byte[] noisyPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0xFFFFFF));
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width / 4, height / 4);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
