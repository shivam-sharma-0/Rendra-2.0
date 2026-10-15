package com.code.pal.agent.contract;

import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.TokenEstimate;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractResponseSchemaTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void plannerGrammarBindsAuthorityAndTypesWithoutReplacingBackendValidation() {
        var c=ContractFixtures.mern();var input=new ContractPlanner.Input(c.id(),"Tasks","Approved","Map",new BuildDecisions.Choice(1,c.storage(),c.auth(),null),c.limits());
        var schema=mapper.readTree(ContractResponseSchema.planner(input,mapper).schema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(c.id().toString(),schema.at("/properties/id/const").asString());assertEquals("MERN",schema.at("/properties/stack/const").asString());
        assertEquals(c.limits().modelCalls(),schema.at("/properties/limits/const/modelCalls").asInt());
        String pattern=schema.at("/properties/acceptance/properties/api/items/properties/assertPointer/anyOf/0/pattern").asString();assertTrue("/0/title".matches(pattern));assertFalse("$.title".matches(pattern));
        String apiPath=schema.at("/properties/acceptance/properties/api/items/properties/path/pattern").asString();assertTrue("/api/items/${recordId}".matches(apiPath));assertFalse("/api/items/:id".matches(apiPath));
    }
    @Test void grammarSurvivesTokenCapChangesAndIsIncludedInReservationEstimate() {
        var plain=new LlmClient.LlmRequest("mistral","codestral-latest",List.of(LlmClient.LlmMessage.user("JSON")),List.of(),0);
        var schema=new LlmClient.JsonSchema("sample","{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
        var bounded=plain.withJsonSchema(schema).withMaxTokens(512);assertEquals(schema,bounded.jsonSchema());assertTrue(TokenEstimate.input(bounded)>TokenEstimate.input(plain));
    }
}
