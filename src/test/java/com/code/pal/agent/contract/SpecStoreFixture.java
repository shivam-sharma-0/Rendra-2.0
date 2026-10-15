package com.code.pal.agent.contract;

import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disposable SQL fixture only; no real project records or provider requests. */
final class SpecStoreFixture {
    final ContractStoreIT.Fixture base;
    final ContractStore.Binding basis;
    final BuildContract contract;
    final ApprovedSpec spec;

    SpecStoreFixture(ContractStoreIT helper) {
        base = helper.new Fixture();
        String json = helper.jdbc.queryForObject("select payload_json from requirement_projections where id=?", String.class, base.projection);
        Map<String, Object> payload = helper.mapper.readValue(json, Map.class);
        payload.put("specJson", helper.mapper.writeValueAsString(SpecFixtures.approved().spec()));
        helper.jdbc.update("update requirement_projections set payload_json=? where id=?", helper.mapper.writeValueAsString(payload), base.projection);
        basis = helper.store.current(base.project);
        spec = new ApprovedSpec(1, basis.specId(), basis.specRevision(), basis.specHash(), base.project,
                basis.requirementsProjection(), basis.requirementsRevision(), ApprovedSpec.Status.APPROVED,
                "sha256:" + basis.capabilityRevision(), SpecFixtures.approved().desired(), ApprovedSpec.Readiness.READY, SpecFixtures.approved().spec());
        var layout = new DeterministicPlan(helper.mapper).plan(spec, new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true)).orElseThrow();
        var page = new BuildContract.Unit("page", "Bakery page", List.of("index.html"), List.of(), List.of(), layout.batch().acceptanceIds());
        var style = new BuildContract.Unit("style", "Bakery style", List.of("styles.css"), List.of(), List.of(), List.of());
        var old = ContractPlans.fromLayout(UUID.randomUUID(), spec, layout, base.contract.limits());
        var plan = new BuildContract(1, old.id(), old.stack(), old.storage(), old.auth(), old.limits(), old.theme(),
                List.of(new BuildContract.Route("/", "index.html", "page")), old.types(), old.models(), old.api(), old.assets(),
                old.envNames(), List.of(page, style), old.acceptance());
        contract = ContractPlans.bind(plan, spec, List.of(new ContractBatch("app", List.of("page", "style"), List.of(),
                List.of("index.html", "styles.css"), List.of(), page.acceptanceIds(), 32_000, 6_000, 4_000)));
    }
}
