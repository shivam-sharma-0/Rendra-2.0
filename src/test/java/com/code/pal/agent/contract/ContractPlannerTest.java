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
        var result=planner.plan(input,model,request->{purposes.add(LlmCallContext.current().purpose());assertEquals(8192,request.maxTokens());assertTrue(request.tools().isEmpty());return count.getAndIncrement()==0?reply("invalid"):reply(mapper.writeValueAsString(contract));});
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
}
