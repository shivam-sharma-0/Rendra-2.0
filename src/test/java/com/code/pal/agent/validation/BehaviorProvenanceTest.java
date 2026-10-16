package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorProvenanceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String LEGACY = """
            {"schema":1,"browser":[{"id":"link_0","kind":"ASSERT_COUNT","selector":"a","value":"1"}],
             "api":[],"authRequired":false,"crudRequired":false}
            """;

    @Test
    void legacyJsonRemainsBlockingAndKeepsItsSerializedShape() {
        var criteria = mapper.readValue(LEGACY, BehaviorCriteria.class);
        assertFalse(criteria.modelSpec());
        assertFalse(mapper.readTree(mapper.writeValueAsString(criteria)).has("provenance"));
        assertEquals(mapper.readTree(LEGACY), mapper.readTree(mapper.writeValueAsString(criteria)));
    }

    @Test
    void modelSpecProvenanceSurvivesPersistence() {
        var criteria = mapper.readValue(LEGACY.replace("\"schema\":1", "\"schema\":1,\"provenance\":\"MODEL_SPEC\""), BehaviorCriteria.class);
        assertTrue(criteria.modelSpec());
        assertEquals(criteria, mapper.readValue(mapper.writeValueAsString(criteria), BehaviorCriteria.class));
    }

    @Test
    void unknownProvenanceIsNotSilentlyTreatedAsAdvisory() {
        assertThrows(RuntimeException.class, () -> mapper.readValue(LEGACY.replace("\"schema\":1",
                "\"schema\":1,\"provenance\":\"UNTRUSTED\""), BehaviorCriteria.class));
    }

    @Test
    void apiChecksCannotBeDowngradedToAdvisory() {
        String api = LEGACY.replace("\"api\":[]", "\"api\":[{\"id\":\"health\",\"kind\":\"HEALTH\",\"method\":\"GET\",\"path\":\"/api/health\",\"status\":200}]");
        assertDoesNotThrow(() -> mapper.readValue(api, BehaviorCriteria.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(api.replace("\"schema\":1",
                "\"schema\":1,\"provenance\":\"MODEL_SPEC\""), BehaviorCriteria.class));
    }
}
