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

    @Test void routeAssertionsRequireAValueWhileClicksStillPermitNull() {
        var c = ContractFixtures.mern();
        var input = new ContractPlanner.Input(c.id(), "Tasks", "Approved", "Map",
                new BuildDecisions.Choice(1, c.storage(), c.auth(), null), c.limits());
        var schema = mapper.readTree(ContractResponseSchema.planner(input, mapper).schema());
        var variants = schema.at("/properties/acceptance/properties/browser/items/anyOf");
        assertEquals(2, variants.size());
        var route = variants.get(0);
        assertEquals("ASSERT_ROUTE", route.at("/properties/kind/const").asString());
        assertEquals("string", route.at("/properties/value/type").asString());
        assertEquals(1, route.at("/properties/value/minLength").asInt());
        assertEquals("^/", route.at("/properties/value/pattern").asString());
        assertTrue(mapper.convertValue(route.path("required"), List.class).contains("value"));
        assertFalse(mapper.convertValue(variants.get(1).at("/properties/kind/enum"), List.class).contains("ASSERT_ROUTE"));
        assertEquals("null", variants.get(1).at("/properties/value/anyOf/1/type").asString());
    }
    @Test void plannerGrammarBindsAuthorityAndTypesWithoutReplacingBackendValidation() {
        var c=ContractFixtures.mern();var input=new ContractPlanner.Input(c.id(),"Tasks","Approved","Map",new BuildDecisions.Choice(1,c.storage(),c.auth(),null),c.limits());
        var schema=mapper.readTree(ContractResponseSchema.planner(input,mapper).schema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(c.id().toString(),schema.at("/properties/id/const").asString());assertEquals("MERN",schema.at("/properties/stack/const").asString());
        assertEquals(c.limits().modelCalls(),schema.at("/properties/limits/const/modelCalls").asInt());
        var environment = mapper.convertValue(schema.at("/properties/envNames/items/enum"), List.class);
        assertTrue(environment.contains("MONGODB_URI"));
        assertFalse(environment.contains("JWT_SECRET"));
        String pattern=schema.at("/properties/acceptance/properties/api/items/properties/assertPointer/anyOf/0/pattern").asString();assertTrue("/0/title".matches(pattern));assertFalse("$.title".matches(pattern));
        String apiPath=schema.at("/properties/acceptance/properties/api/items/properties/path/pattern").asString();assertTrue("/api/items/${recordId}".matches(apiPath));assertFalse("/api/items/:id".matches(apiPath));
    }
    @Test void grammarSurvivesTokenCapChangesAndIsIncludedInReservationEstimate() {
        var plain=new LlmClient.LlmRequest("mistral","codestral-latest",List.of(LlmClient.LlmMessage.user("JSON")),List.of(),0);
        var schema=new LlmClient.JsonSchema("sample","{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
        var bounded=plain.withJsonSchema(schema).withMaxTokens(512);assertEquals(schema,bounded.jsonSchema());assertTrue(TokenEstimate.input(bounded)>TokenEstimate.input(plain));
    }

    @Test void accountApisArePinnedToTheCoordinatorGuardOnlyForAccounts() {
        var accounts = ContractFixtures.accounts();
        for (String root : Arrays.asList(null, "client/src/App.tsx")) {
            var input = new ContractPlanner.Input(accounts.id(), "Blog", "Approved", "Map",
                    new BuildDecisions.Choice(1, accounts.storage(), accounts.auth(), null), accounts.limits(), root);
            var schema = mapper.readTree(ContractResponseSchema.planner(input, mapper).schema());
            assertTrue(schema.at("/properties/api/items/properties/authenticated/const").asBoolean());
            assertEquals(root == null, schema.at("/properties/api/items/properties/routerPath/pattern").isMissingNode());
        }
        var open = ContractFixtures.mern();
        var input = new ContractPlanner.Input(open.id(), "Tasks", "Approved", "Map",
                new BuildDecisions.Choice(1, open.storage(), open.auth(), null), open.limits(), "client/src/App.tsx");
        var schema = mapper.readTree(ContractResponseSchema.planner(input, mapper).schema());
        assertTrue(schema.at("/properties/api/items/properties/authenticated/const").isMissingNode());
        assertEquals("boolean", schema.at("/properties/api/items/properties/authenticated/type").asString());
    }

    @Test void freshMernFileMetadataUsesCompletePathsAndAutomaticDependencyContext() {
        var contract = ContractFixtures.mern();
        var choice = new BuildDecisions.Choice(1, contract.storage(), contract.auth(), null);
        var input = new ContractPlanner.Input(contract.id(), "Tasks", "Approved", "Map", choice,
                contract.limits(), "client/src/App.tsx");
        var schema = mapper.readTree(ContractResponseSchema.planner(input, mapper).schema());
        assertEquals(0, schema.at("/properties/units/items/properties/imports/const").size());
        String pattern = schema.at("/properties/units/items/properties/ownedPaths/items/pattern").asString();
        assertTrue("client/src/pages/TaskDetail.tsx".matches(pattern));
        for (String path : List.of("./client/src/App.tsx", "../shared.ts", "client/src/rendra/<id>/api.ts",
                "client/src/pages/[id].tsx", "/etc/passwd")) assertFalse(path.matches(pattern));
        var existing = new ContractPlanner.Input(contract.id(), "Update tasks", "Approved", "Map", choice, contract.limits());
        assertTrue(mapper.readTree(ContractResponseSchema.planner(existing, mapper).schema())
                .at("/properties/units/items/properties/imports/const").isMissingNode());
    }
}
