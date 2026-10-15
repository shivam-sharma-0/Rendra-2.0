package com.code.pal.agent.contract;

import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecNormalizer;
import tools.jackson.databind.ObjectMapper;

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

    /** The same normalized body ApprovedSpecs approves, so binding and envelope hashes always agree. */
    public static ApprovedSpec.Spec body(String json, BuildDecisions.Storage storage, ObjectMapper mapper) {
        var node = mapper.readTree(json);
        if (!node.isObject()) {
            throw new IllegalArgumentException("Spec draft is not an object");
        }
        return SpecNormalizer.normalize(node, ApprovedSpec.Storage.valueOf(storage.name()));
    }
}
