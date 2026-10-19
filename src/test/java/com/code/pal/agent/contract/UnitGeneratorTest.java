package com.code.pal.agent.contract;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UnitGeneratorTest {
    final ObjectMapper mapper=new ObjectMapper();final UnitGenerator generator=new UnitGenerator(mapper);final BuildContract c=ContractFixtures.staticSite();
    final UnitGenerator.Input input=new UnitGenerator.Input(c,c.units().getFirst(),"Build calculator","Approved",Map.of("index.html",new UnitGenerator.FileView("absent",null)),"");
    final AgentProperties.ModelSelection model=new AgentProperties.ModelSelection("fake","fake");
    LlmClient.LlmResult reply(String text){return new LlmClient.LlmResult(text,null,List.of(),new LlmClient.Usage(20,30),"stop");}
    @Test void scopedDataHasNoToolsAndCorrectMeteringPurpose() {
        var result=generator.generate(input,model,r->{assertEquals(CallPurpose.UNIT,LlmCallContext.current().purpose());assertTrue(r.tools().isEmpty());return reply(mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html","absent","<main>Calculator</main>")))));});
        assertEquals("index.html",result.files().getFirst().path());
    }
    @Test void foreignOrStaleOrMissingOwnedOutputsCannotMergeAndCorrectionIsBounded() {
        for(var delta:List.of(new UnitGenerator.Delta("foreign.html","absent","foreign"),new UnitGenerator.Delta("index.html","stale","stale"))){var calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->generator.generate(input,model,r->{calls.incrementAndGet();return reply(mapper.writeValueAsString(new UnitGenerator.Output(List.of(delta))));}));assertEquals(2,calls.get());}
        assertThrows(IllegalArgumentException.class,()->UnitGenerator.validate(input,new UnitGenerator.Output(List.of())));
    }
    @Test void cancellationNeverTriggersCorrection() {var calls=new AtomicInteger();assertThrows(LlmCancelledException.class,()->generator.generate(input,model,r->{calls.incrementAndGet();throw new LlmCancelledException();}));assertEquals(1,calls.get());}

    @Test void completeProductContextUsesTheConfiguredBoundAndRefusesOversizeBeforeSending() {
        var properties = new AgentProperties();
        var scoped = new UnitGenerator.Input(c, c.units().getFirst(), input.request(),
                "Approved full product scope. ".repeat(1500), input.snapshot(), "");
        var configured = new UnitGenerator(mapper, properties);
        var calls = new AtomicInteger();
        configured.generate(scoped, model, request -> {
            calls.incrementAndGet();
            return reply(mapper.writeValueAsString(new UnitGenerator.Output(List.of(
                    new UnitGenerator.Delta("index.html", "absent", "<main>Calculator</main>")))));
        });
        assertEquals(1, calls.get());
        properties.setContextBudgetChars(32000);
        assertThrows(IllegalArgumentException.class, () -> configured.generate(scoped, model, request -> {
            calls.incrementAndGet();
            throw new AssertionError("Oversized input must not reach the provider");
        }));
        assertEquals(1, calls.get());
    }
}
