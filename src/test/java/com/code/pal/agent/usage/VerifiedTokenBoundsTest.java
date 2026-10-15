package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedTokenBoundsTest {
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
