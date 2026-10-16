package com.code.pal.agent.contract;

import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class ContractProvenanceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aModelProposedContractCannotDowngradeItsOwnChecks() {
        String json = mapper.writeValueAsString(ContractFixtures.staticSite());
        String forged = json.replace("\"acceptance\":{", "\"acceptance\":{\"provenance\":\"MODEL_SPEC\",");
        assertNotEquals(json, forged);
        var failure = assertThrows(RuntimeException.class, () -> mapper.readValue(forged, BuildContract.class));
        assertTrue(failure.getMessage().contains("provenance"));
    }

    @Test
    void plannerGrammarDoesNotLetTheModelChooseProvenance() {
        var contract = ContractFixtures.staticSite();
        var input = new ContractPlanner.Input(contract.id(), "Site", "Approved", "Map",
                new BuildDecisions.Choice(1, contract.storage(), contract.auth(), null), contract.limits());
        var schema = mapper.readTree(ContractResponseSchema.planner(input, mapper).schema());
        assertFalse(schema.at("/properties/acceptance/properties").has("provenance"));
        assertFalse(schema.at("/properties/acceptance/required").toString().contains("provenance"));
    }
}
