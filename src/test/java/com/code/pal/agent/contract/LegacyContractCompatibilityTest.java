package com.code.pal.agent.contract;

import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LegacyContractCompatibilityTest {
    private static final List<String> V2_FIELDS = List.of("specId", "specRevision", "specHash", "planRevision",
            "replacesContractId", "templateVersion", "batches");
    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<Arguments> historicalContracts() {
        return Stream.of(ContractFixtures.staticSite(), ContractFixtures.vite(), ContractFixtures.mern())
                .flatMap(contract -> Stream.of(Arguments.of(contract.stack(), contract, false), Arguments.of(contract.stack(), contract, true)));
    }

    @ParameterizedTest(name = "{0}, explicit null metadata={2}")
    @MethodSource("historicalContracts")
    void historicalV1JsonRemainsReadableWithMissingOrNullV2Metadata(BuildContract.Stack stack, BuildContract expected, boolean explicitNull) {
        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(expected), Map.class);
        for (String field : V2_FIELDS) {
            if (explicitNull) {
                json.put(field, null);
            } else {
                json.remove(field);
            }
        }
        var actual = mapper.readValue(mapper.writeValueAsString(json), BuildContract.class);
        assertEquals(expected, actual);
        assertEquals(stack, actual.stack());
        var output = mapper.readTree(mapper.writeValueAsString(actual));
        V2_FIELDS.forEach(field -> assertFalse(output.has(field), "v1 must not gain " + field));
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"specRevision", "planRevision"})
    void v2StillRequiresPositiveNonNullRevisions(String field) {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(contract), Map.class);
        json.put(field, null);
        var error = assertThrows(RuntimeException.class, () -> mapper.readValue(mapper.writeValueAsString(json), BuildContract.class));
        assertTrue(causes(error).anyMatch(cause -> "Invalid versioned plan identity".equals(cause.getMessage())),
                "Missing v2 revision must fail the contract invariant, rather than primitive null decoding");
    }

    @ParameterizedTest
    @ValueSource(strings = {"specRevision", "planRevision"})
    void v1CannotCarryANonzeroV2Revision(String field) {
        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(ContractFixtures.staticSite()), Map.class);
        json.put(field, 1);
        var error = assertThrows(RuntimeException.class, () -> mapper.readValue(mapper.writeValueAsString(json), BuildContract.class));
        assertTrue(causes(error).anyMatch(cause -> "Legacy contract cannot carry partial versioned metadata".equals(cause.getMessage())));
    }

    private static Stream<Throwable> causes(Throwable error) {
        return Stream.iterate(error, cause -> cause != null, Throwable::getCause);
    }
}
