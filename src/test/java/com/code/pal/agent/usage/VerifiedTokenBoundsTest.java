package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedTokenBoundsTest {
    @Test void selectedOpenRouterPlannerHasTextAccountingAndIndependentConfigOverrides() {
        var properties = new AgentProperties();
        var request = new LlmClient.LlmRequest("openrouter", "nvidia/nemotron-3-ultra-550b-a55b:free",
                List.of(LlmClient.LlmMessage.user("Plan a task application")), List.of(), 0).withMaxTokens(6000);
        assertEquals(65536, VerifiedTokenBounds.input(properties, request));
        assertTrue(VerifiedTokenBounds.reservationInput(properties, request) < 1000);
        properties.getAccountingBounds().get("openrouter:" + request.model()).setOutputTokens(1000);
        assertThrows(LlmAccountingException.class, () -> VerifiedTokenBounds.input(properties, request));
        assertEquals(65536, VerifiedTokenBounds.input(new AgentProperties(), request),
                "An operator override must not mutate the next configuration instance");
        var image = new LlmClient.LlmRequest(request.provider(), request.model(),
                List.of(LlmClient.LlmMessage.userWithImages("Plan", List.of("data:image/png;base64,AA=="))),
                List.of(), 0).withMaxTokens(6000);
        assertThrows(LlmAccountingException.class, () -> VerifiedTokenBounds.input(new AgentProperties(), image));
    }
    @Test void shippedModelsHaveWorkingCapsAndReserveTheRequestEstimate(){
        var p=new AgentProperties();for(String key:p.getAccountingBounds().keySet()){int colon=key.indexOf(':');var r=new LlmClient.LlmRequest(key.substring(0,colon),key.substring(colon+1),List.of(LlmClient.LlmMessage.user("Build a calculator")),List.of(),0).withMaxTokens(8192);
            assertEquals(65536,VerifiedTokenBounds.input(p,r));assertTrue(VerifiedTokenBounds.reservationInput(p,r)<1000);}
    }
    @Test void missingVerificationCannotSendEvenWithUnlimitedQuota() {
        var p=new AgentProperties();p.getLimits().setDailyTokenQuota(0);
        var r=new LlmClient.LlmRequest("fake","model",List.of(),List.of(),0).withMaxTokens(10);
        assertThrows(LlmAccountingException.class,()->VerifiedTokenBounds.input(p,r));
        var b=new AgentProperties.AccountingBound();b.setInputTokens(100);b.setOutputTokens(10);p.getAccountingBounds().put("fake:model",b);
        assertThrows(LlmAccountingException.class,()->VerifiedTokenBounds.input(p,r));
        b.setEvidence("Controlled fixture"); assertEquals(100,VerifiedTokenBounds.input(p,r));
        assertThrows(LlmAccountingException.class,()->VerifiedTokenBounds.input(p,r.withMaxTokens(11)));
        assertThrows(LlmAccountingException.class,()->VerifiedTokenBounds.input(p,r.withMaxTokens(0)));
    }
    @Test void imageAccountingRequiresItsOwnVerification() {
        var p=new AgentProperties();var b=new AgentProperties.AccountingBound();b.setInputTokens(100);b.setOutputTokens(10);b.setEvidence("Controlled fixture");p.getAccountingBounds().put("fake:model",b);
        var r=new LlmClient.LlmRequest("fake","model",List.of(LlmClient.LlmMessage.userWithImages("hi",List.of("data:image/png;base64,AA=="))),List.of(),0).withMaxTokens(10);
        assertThrows(LlmAccountingException.class,()->VerifiedTokenBounds.input(p,r));
        b.setImages(true);assertEquals(100,VerifiedTokenBounds.input(p,r));
    }
}
