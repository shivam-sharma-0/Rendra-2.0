package com.code.pal.project.spec;

import com.code.pal.agent.contract.ContractStore;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wraps the spec body approved with the current requirements projection in the server-owned envelope.
 * Identity, revisions, capabilities, persistence and assets come from the server, never from the draft;
 * anything stale, legacy or invalid yields no spec and the build plans from the answers as before.
 */
@Slf4j
@Service
public class ApprovedSpecs {
    private static final Set<String> STATIC_FEATURES = Set.of("CONTENT", "CONTACT_LINK", "NAV_ANCHOR");

    private final ApprovedProjectMemory memory;
    private final ContractStore contracts;
    private final BuildDecisions decisions;
    private final ObjectMapper mapper;

    public ApprovedSpecs(ApprovedProjectMemory memory, ContractStore contracts, BuildDecisions decisions,
            ObjectMapper mapper) {
        this.memory = memory;
        this.contracts = contracts;
        this.decisions = decisions;
        this.mapper = mapper;
    }

    public Optional<ApprovedSpec> current(UUID projectId) {
        var approved = memory.current(projectId);
        if (approved == null || approved.specJson() == null) {
            return Optional.empty();
        }
        try {
            var choice = decisions.state(projectId).choice();
            if (choice == null || choice.storage() == BuildDecisions.Storage.MERN) {
                return Optional.empty();
            }
            var binding = contracts.current(projectId);
            if (!binding.requirementsProjection().equals(approved.projectionId())
                    || binding.requirementsRevision() != approved.revision()) {
                return Optional.empty();
            }
            var storage = ApprovedSpec.Storage.valueOf(choice.storage().name());
            var spec = body(approved.specJson(), storage);
            var desired = new ApprovedSpec.Desired(renderStack(spec, storage), storage,
                    ApprovedSpec.Auth.valueOf(choice.auth().name()));
            UUID specId = UUID.nameUUIDFromBytes(("spec:" + approved.projectionId()).getBytes(StandardCharsets.UTF_8));
            return Optional.of(new ApprovedSpec(1, specId, approved.revision() + 1,
                    "sha256:" + ContractStore.hash(mapper.writeValueAsString(spec)), projectId, approved.projectionId(),
                    approved.revision(), ApprovedSpec.Status.APPROVED, "sha256:" + binding.capabilityRevision(), desired,
                    ApprovedSpec.Readiness.READY, spec));
        } catch (RuntimeException unusable) {
            log.info("approved_spec_unavailable project={} reason={}", projectId, unusable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private ApprovedSpec.Spec body(String json, ApprovedSpec.Storage storage) {
        var node = (ObjectNode) mapper.readTree(json);
        node.putArray("assets");
        if (!node.path("language").isString()) {
            node.put("language", "en");
        }
        for (var entity : node.path("entities")) {
            if (entity instanceof ObjectNode object) {
                object.put("persistence", storage.name());
            }
        }
        return mapper.readerFor(ApprovedSpec.Spec.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(mapper.writeValueAsString(node));
    }

    /** No saved data, one page and only content features render as a static site; anything else uses Vite. */
    static ApprovedSpec.RenderStack renderStack(ApprovedSpec.Spec spec, ApprovedSpec.Storage storage) {
        boolean staticSite = storage == ApprovedSpec.Storage.NONE && spec.pages().size() == 1 && spec.entities().isEmpty()
                && spec.features().stream().allMatch(feature -> STATIC_FEATURES.contains(feature.kind()));
        return staticSite ? ApprovedSpec.RenderStack.STATIC : ApprovedSpec.RenderStack.VITE;
    }
}
