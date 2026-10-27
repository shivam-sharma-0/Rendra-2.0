package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BatchUnitGeneratorTest {
    final ObjectMapper mapper=new ObjectMapper();final BatchUnitGenerator generator=new BatchUnitGenerator(mapper);
    final AgentProperties.ModelSelection model=new AgentProperties.ModelSelection("mistral","test");
    List<UnitGenerator.Input> inputs() {
        var c=BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of()),BuildContractTest.unit("style","style.css",List.of())));
        return c.units().stream().map(u->new UnitGenerator.Input(c,u,"Build a calculator","Approved",Map.of(u.ownedPaths().getFirst(),new UnitGenerator.FileView("absent",null)),"")).toList();
    }
    LlmClient.LlmResult response(List<UnitGenerator.Input> inputs) {
        var output=new BatchUnitGenerator.Output(inputs.stream().map(in->new BatchUnitGenerator.UnitOutput(in.unit().id(),List.of(new UnitGenerator.Delta(in.unit().ownedPaths().getFirst(),"absent","generated")))).toList());
        return new LlmClient.LlmResult(mapper.writeValueAsString(output),null,List.of(),new LlmClient.Usage(10,20),"stop");
    }
    @Test void twoIndependentUnitsUseOneCallAndBindEveryFileToItsOwner() {
        var inputs=inputs();var baselineCalls=new AtomicInteger();var separate=new UnitGenerator(mapper);
        var baseline=inputs.stream().map(in->separate.generate(in,model,request->{baselineCalls.incrementAndGet();
            return new LlmClient.LlmResult(mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta(in.unit().ownedPaths().getFirst(),"absent","generated")))),null,List.of(),new LlmClient.Usage(10,20),"stop");})).toList();
        var calls=new AtomicInteger();var output=generator.generate(inputs,model,request->{calls.incrementAndGet();
            assertEquals(CallPurpose.UNIT,LlmCallContext.current().purpose());assertTrue(request.tools().isEmpty());assertNotNull(request.jsonSchema());
            return response(inputs);});
        assertEquals(2,baselineCalls.get());assertEquals(1,calls.get());assertEquals(baseline,output);
        assertEquals(2,output.size());assertEquals("index.html",output.getFirst().files().getFirst().path());
    }
    @Test void foreignPathsAndPartialOrDuplicateUnitOutputsNeverPass() {
        var inputs=inputs();var foreign=new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput("page",List.of(new UnitGenerator.Delta("style.css","absent","bad"))),new BatchUnitGenerator.UnitOutput("style",List.of(new UnitGenerator.Delta("style.css","absent","ok")))));
        assertThrows(IllegalArgumentException.class,()->BatchUnitGenerator.validate(inputs,foreign));
        assertThrows(IllegalArgumentException.class,()->BatchUnitGenerator.validate(inputs,new BatchUnitGenerator.Output(List.of())));
        assertThrows(IllegalArgumentException.class,()->BatchUnitGenerator.validate(inputs,new BatchUnitGenerator.Output(List.of(foreign.units().getFirst(),foreign.units().getFirst()))));
    }
    @Test void correctionIsBoundedAndProviderFailuresAreNotFormatRetries() {
        var count=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->generator.generate(inputs(),model,r->{count.incrementAndGet();return new LlmClient.LlmResult("{}",null,List.of(),null,"stop");}));assertEquals(2,count.get());
        count.set(0);assertThrows(LlmCancelledException.class,()->generator.generate(inputs(),model,r->{count.incrementAndGet();throw new LlmCancelledException();}));assertEquals(1,count.get());
    }
    @Test void dependentOrOversizedInputsKeepSeparateCalls() {
        var c=ContractFixtures.mern();var dependent=c.units().stream().map(u->new UnitGenerator.Input(c,u,"Build","Approved",Map.of(),"")).toList();assertFalse(generator.eligible(dependent));
        var original=inputs();var first=original.getFirst();var huge=new UnitGenerator.Input(first.contract(),first.unit(),first.request(),first.approvedRequirements(),Map.of("index.html",new UnitGenerator.FileView("absent","x".repeat(40_000))),"");
        assertFalse(generator.eligible(List.of(huge,original.getLast())));
        // Live E2E 2026-10-28: a pair under the old character estimate exceeded generate()'s byte bound (policy + schema).
        var accented=new UnitGenerator.Input(first.contract(),first.unit(),first.request(),first.approvedRequirements(),Map.of("index.html",new UnitGenerator.FileView("absent","é".repeat(15_500))),"");
        assertTrue(new ObjectMapper().writeValueAsString(accented).length()<32_000,"the old character estimate admitted it");
        assertFalse(generator.eligible(List.of(accented,original.getLast())));
    }
    @Test void batchCheckpointInputsIncludePartnerVersionsAndRejectInconsistentFrozenBodies() {
        var inputs=inputs();var frozen=BatchUnitGenerator.checkpointInputs(inputs);assertEquals(Set.of("index.html","style.css"),frozen.getFirst().snapshot().keySet());
        var second=inputs.getLast();var conflicting=new UnitGenerator.Input(second.contract(),second.unit(),second.request(),second.approvedRequirements(),Map.of("index.html",new UnitGenerator.FileView("different","foreign")),"");
        assertThrows(IllegalArgumentException.class,()->BatchUnitGenerator.checkpointInputs(List.of(inputs.getFirst(),conflicting)));
    }
}
