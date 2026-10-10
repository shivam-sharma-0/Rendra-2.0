package com.code.pal.agent.config;

import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPropertiesRoutingTest {

    @Test
    void failedModelIsSkippedWhileOthersAreAvailable() {
        AgentProperties props = props(List.of("a", "b", "c"));
        ModelSelection b = selection(props, "b");

        props.markModelUnavailable(b);

        assertEquals(List.of("a", "c", "a", "c"), next(props, 4));
    }

    @Test
    void markingAvailableRestoresTheModel() {
        AgentProperties props = props(List.of("a", "b"));
        ModelSelection b = selection(props, "b");
        props.markModelUnavailable(b);
        assertEquals(List.of("a", "a"), next(props, 2));

        props.markModelAvailable(b);

        // Back in rotation (the order depends on where the round-robin cursor stopped).
        assertEquals(java.util.Set.of("a", "b"), java.util.Set.copyOf(next(props, 2)));
    }

    @Test
    void cooldownExpires() throws Exception {
        AgentProperties props = props(List.of("a", "b"));
        props.getLlm().setProviderCooldownMs(1);
        props.markModelUnavailable(selection(props, "b"));

        Thread.sleep(10);

        assertEquals(List.of("a", "b"), next(props, 2));
    }

    @Test
    void aModelListedTwiceThatFailedStillFallsBackToTheOthers() {
        AgentProperties props = props(List.of("a", "a", "b"));
        props.markModelUnavailable(selection(props, "a"));

        assertEquals(List.of("b", "b", "b"), next(props, 3), "the second listing of a cooling model is not the end of the list");
    }

    @Test
    void aRunPrefersTheFirstListedModelAndSkipsItOnlyWhileItFails() {
        AgentProperties props = props(List.of("a", "b", "c"));
        assertEquals("a", props.preferredModel(ProjectType.CHAT).model());
        assertEquals("a", props.preferredModel(ProjectType.CHAT).model(), "not rotated between runs");

        props.markModelUnavailable(selection(props, "a"));
        assertEquals("b", props.preferredModel(ProjectType.CHAT).model());
        assertFalse(props.available(selection(props, "a")));
    }

    @Test
    void whenEveryModelIsCoolingDownOneIsStillReturned() {
        AgentProperties props = props(List.of("a", "b"));
        props.markModelUnavailable(selection(props, "a"));
        props.markModelUnavailable(selection(props, "b"));

        ModelSelection chosen = props.modelFor(ProjectType.CHAT);

        assertNotNull(chosen.model(), "routing must still try a model rather than fail without a call");
    }

    @Test
    void modesWithoutAnyModelAreReported() {
        AgentProperties props = new AgentProperties();
        props.setBillingMode(AgentProperties.BillingMode.FREE);
        props.getModels().setFreeBuild(new ArrayList<>(List.of("mistral:codestral-latest")));
        props.getModels().setFreeChat(new ArrayList<>(List.of(" ")));
        props.getModels().setVision(new ArrayList<>(List.of("nvidia:vision")));
        assertEquals(List.of("CHAT", "PLAN"), props.modesWithoutModels());
    }

    @Test
    void aModelTheAccountCannotUseIsSkippedForTheLongCooldown() throws Exception {
        AgentProperties props = props(List.of("a", "b"));
        ModelSelection a = selection(props, "a");
        props.markModelUnavailable(a, 60_000);
        assertFalse(props.available(a));
        props.markModelUnavailable(a, 1);
        Thread.sleep(5);
        assertTrue(props.available(a), "a short cooldown ends");
    }

    private static AgentProperties props(List<String> chatModels) {
        AgentProperties props = new AgentProperties();
        props.getModels().setChat(new ArrayList<>(chatModels));
        return props;
    }

    /** The selection routing produces for {@code model} (provider resolution included). */
    private static ModelSelection selection(AgentProperties props, String model) {
        AgentProperties probe = props(List.of(model));
        return probe.modelFor(ProjectType.CHAT);
    }

    private static List<String> next(AgentProperties props, int count) {
        List<String> models = new ArrayList<>();
        for (int i = 0; i < count; i++) models.add(props.modelFor(ProjectType.CHAT).model());
        return models;
    }
}
