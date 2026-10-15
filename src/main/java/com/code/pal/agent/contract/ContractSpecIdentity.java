package com.code.pal.agent.contract;

import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Pure normalization shared by approval loading and immutable contract binding; no store dependency cycle. */
public record ContractSpecIdentity(UUID id, long revision, String hash, String documentHash) {
    public static ContractSpecIdentity of(ApprovedProjectMemory.Approved approved, BuildDecisions.Storage storage, ObjectMapper mapper) {
        var body = body(approved.specJson(), storage, mapper);
        return new ContractSpecIdentity(UUID.nameUUIDFromBytes(("spec:" + approved.projectionId()).getBytes(StandardCharsets.UTF_8)),
                Math.addExact(approved.revision(), 1), "sha256:" + ContractStore.hash(mapper.writeValueAsString(body)),
                ContractStore.hash(approved.specJson()));
    }

    public static ApprovedSpec.Spec body(String json, BuildDecisions.Storage storage, ObjectMapper mapper) {
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
}
