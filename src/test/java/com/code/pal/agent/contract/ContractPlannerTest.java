package com.code.pal.agent.contract;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContractPlannerTest {
    final ObjectMapper mapper=new ObjectMapper();final ContractPlanner planner=new ContractPlanner(mapper);
    final BuildContract contract=BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of())));
    final ContractPlanner.Input input=new ContractPlanner.Input(contract.id(),"Calculator","Approved calculator","index.html",new BuildDecisions.Choice(1,contract.storage(),contract.auth(),null),contract.limits());
    final AgentProperties.ModelSelection model=new AgentProperties.ModelSelection("fake","fake");
    @Test void freshRootOwnershipUsesTheExistingCorrectionBudget(){
        var c=ContractFixtures.mern();Map<String,Object> bad=mapper.readValue(mapper.writeValueAsString(c),Map.class);
        var units=(List<Map<String,Object>>)bad.get("units");units.getLast().put("ownedPaths",List.of("client/src/Items.tsx"));
        bad.put("routes",List.of());
        var fresh=new ContractPlanner.Input(c.id(),"Build Items","Approved","Map",new BuildDecisions.Choice(1,c.storage(),c.auth(),null),c.limits(),"client/src/App.tsx");var calls=new AtomicInteger();
        assertEquals(c,planner.plan(fresh,model,request->{if(calls.incrementAndGet()==1)return reply(mapper.writeValueAsString(bad));assertTrue(request.messages().getLast().content().contains("root component"));return reply(mapper.writeValueAsString(c));}));assertEquals(2,calls.get());
    }
    @Test void freshRequestedCrudCannotBeCertifiedByAHeadingOnly(){
        var c=ContractFixtures.mern();var fresh=new ContractPlanner.Input(c.id(),"Add, edit and delete tasks with completed","Approved","Map",new BuildDecisions.Choice(1,c.storage(),c.auth(),null),c.limits(),"client/src/App.tsx");var calls=new AtomicInteger();
        var failure=assertThrows(IllegalArgumentException.class,()->planner.plan(fresh,model,request->{calls.incrementAndGet();return reply(mapper.writeValueAsString(c));}));assertEquals(2,calls.get());assertTrue(failure.getMessage().contains("Requested UI CRUD"));
    }
    LlmClient.LlmResult reply(String body){return new LlmClient.LlmResult(body,null,List.of(),new LlmClient.Usage(20,30),"stop");}
    @Test void oneValidPlannerCallAndOneBoundedCorrectionHaveDistinctMeteringPurposes() {
        var count=new AtomicInteger();var purposes=new ArrayList<CallPurpose>();
        var result=planner.plan(input,model,request->{purposes.add(LlmCallContext.current().purpose());assertEquals(12288,request.maxTokens());assertTrue(request.tools().isEmpty());assertNull(request.toolChoice());assertNull(request.jsonSchema());assertTrue(request.messages().getFirst().content().contains("Do not return tool calls"));return count.getAndIncrement()==0?reply("invalid"):reply(mapper.writeValueAsString(contract));});
        assertEquals(contract,result);assertEquals(2,count.get());assertEquals(List.of(CallPurpose.PLANNER,CallPurpose.SCHEMA_CORRECTION),purposes);
        count.set(0);planner.plan(input,model,r->{count.incrementAndGet();return reply(mapper.writeValueAsString(contract));});assertEquals(1,count.get());
    }
    @Test void malformedOrRaisedLimitsNeverCauseAThirdCall() {
        var count=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->planner.plan(input,model,r->{count.incrementAndGet();return reply(mapper.writeValueAsString(contract).replace("\"modelCalls\":10","\"modelCalls\":99"));}));assertEquals(2,count.get());
    }
    @Test void stopAndQuotaRefusalPropagateWithoutSchemaRetry() {
        var count=new AtomicInteger();assertThrows(LlmCancelledException.class,()->planner.plan(input,model,r->{count.incrementAndGet();throw new LlmCancelledException();}));assertEquals(1,count.get());
    }
    @Test void aSingleJsonFenceDoesNotSpendACorrectionCall() {
        var calls=new AtomicInteger();assertEquals(contract,planner.plan(input,model,r->{calls.incrementAndGet();return reply("```json\n"+mapper.writeValueAsString(contract)+"\n```");}));assertEquals(1,calls.get());
    }
    @Test void trailingObjectsAndProseAreRejectedWithoutRelaxingAuthorityChecks() {
        for(String body:List.of(mapper.writeValueAsString(contract)+"{}","Here is the plan: "+mapper.writeValueAsString(contract))){var calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->planner.plan(input,model,r->{calls.incrementAndGet();return reply(body);}));assertEquals(2,calls.get());}
    }
    @Test void nestedDecoderFailuresGiveFieldFeedbackWithoutCopyingModelText() {
        var calls=new AtomicInteger();String malformed=mapper.writeValueAsString(contract).replace("\"ownedPaths\":[\"index.html\"]","\"ownedPaths\":\"PRIVATE_MODEL_TEXT\"");assertNotEquals(mapper.writeValueAsString(contract),malformed);
        assertEquals(contract,planner.plan(input,model,request->{if(calls.incrementAndGet()==1)return reply(malformed);String correction=request.messages().getLast().content();assertTrue(correction.contains("ownedPaths"));assertFalse(correction.contains("PRIVATE_MODEL_TEXT"));return reply(mapper.writeValueAsString(contract));}));assertEquals(2,calls.get());
    }
    @Test void schemaBoundProposalToolReturnsDataWithoutAdvertisingMutation() {
        var properties = new AgentProperties();
        properties.getPlanning().setContractToolEnabled(true);
        assertEquals(contract, new ContractPlanner(mapper, properties).plan(input, model, request -> {
            assertEquals(List.of("proposeContract"), request.tools().stream().map(LlmClient.ToolSpec::name).toList());
            assertEquals("object", request.tools().getFirst().parameters().get("type"));
            return new LlmClient.LlmResult("", null, List.of(new LlmClient.ToolCall("proposal", "proposeContract",
                    mapper.writeValueAsString(contract))), new LlmClient.Usage(20, 30), "tool_calls");
        }));
    }

    @Test void malformedPrimaryUsesOnlyTheConfiguredPlannerForTheExistingCorrectionSlot() {
        var properties = new AgentProperties();
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getPlanning().setSchemaProviders(Set.of("mistral"));
        properties.getModels().setPlan(List.of("openrouter:primary", "mistral:secondary"));
        var primary = new AgentProperties.ModelSelection("openrouter", "primary");
        var routes = new ArrayList<String>();
        assertEquals(contract, new ContractPlanner(mapper, properties).plan(input, primary, request -> {
            routes.add(request.provider() + ":" + request.model());
            assertTrue(request.tools().isEmpty());
            if (routes.size() == 1) assertNull(request.jsonSchema());
            else assertNotNull(request.jsonSchema());
            return routes.size() == 1 ? reply("[[{\"name\":\"proposeContract\",\"parameters\":{} }]")
                    : reply(mapper.writeValueAsString(contract));
        }));
        assertEquals(List.of("openrouter:primary", "mistral:secondary"), routes);
    }

    @Test void malformedJsonIsSuppliedAsUntrustedCorrectionDataInsteadOfStartingBlindly() {
        var calls = new AtomicInteger();
        String malformed = mapper.writeValueAsString(contract).replace("\"imports\":[]", "\"imports\":[],");
        assertEquals(contract, planner.plan(input, model, request -> {
            if (calls.incrementAndGet() == 1) return reply(malformed);
            assertTrue(request.messages().stream().anyMatch(message -> message.content().contains(malformed)));
            return reply(mapper.writeValueAsString(contract));
        }));
        assertEquals(2, calls.get());
    }

    @Test void singletonToolEnvelopePreservesOneValidatedProposalAndRejectsMultipleContracts() {
        assertEquals(contract, planner.plan(input, model, request -> reply(mapper.writeValueAsString(List.of(contract)))));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(input, model, request -> reply(mapper.writeValueAsString(List.of(contract)) + "{}")));
        assertEquals(contract, planner.plan(input, model, request -> new LlmClient.LlmResult("", null,
                List.of(new LlmClient.ToolCall("proposal", "proposeContract", mapper.writeValueAsString(List.of(contract)))),
                new LlmClient.Usage(20, 30), "tool_calls")));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(input, model, request -> new LlmClient.LlmResult("", null,
                List.of(new LlmClient.ToolCall("proposal", "proposeContract", mapper.writeValueAsString(List.of(contract, contract)))),
                new LlmClient.Usage(20, 30), "tool_calls")));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(input, model, request -> new LlmClient.LlmResult("", null,
                List.of(new LlmClient.ToolCall("proposal", "proposeContract", mapper.writeValueAsString(List.of(contract)) + "{}")),
                new LlmClient.Usage(20, 30), "tool_calls")));
    }

    @Test void fencedSingletonProposalUsesOneCallWhileAmbiguousAndRaisedAuthorityRemainRejected() {
        for (boolean tool : List.of(false, true)) {
            String singleton = "```json\n" + mapper.writeValueAsString(List.of(contract)) + "\n```";
            var calls = new AtomicInteger();
            assertEquals(contract, planner.plan(input, model, request -> {
                calls.incrementAndGet();
                return tool ? new LlmClient.LlmResult("", null,
                        List.of(new LlmClient.ToolCall("proposal", "proposeContract", singleton)),
                        new LlmClient.Usage(20, 30), "tool_calls") : reply(singleton);
            }));
            assertEquals(1, calls.get());
            for (String invalid : List.of(
                    "```json\n" + mapper.writeValueAsString(List.of(contract, contract)) + "\n```",
                    "```json\n" + mapper.writeValueAsString(List.of(contract)) + "{}\n```",
                    singleton.replace("\"modelCalls\":10", "\"modelCalls\":99"))) {
                assertThrows(IllegalArgumentException.class, () -> planner.plan(input, model, request -> reply(invalid)));
            }
        }
    }
}
