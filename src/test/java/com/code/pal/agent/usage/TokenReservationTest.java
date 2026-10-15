package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Policy wiring only; real capacity races and settlement invariants are exercised in DailyTokenBudgetIT. */
class TokenReservationTest {
    private final AgentProperties props = new AgentProperties();
    private final DailyTokenBudget budget = mock(DailyTokenBudget.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AgentUsagePolicy policy = new AgentUsagePolicy(props, mock(AgentRunRepository.class), redis,
            Clock.fixed(Instant.parse("2026-10-03T05:30:00Z"), ZoneOffset.UTC), budget);
    private final LlmCallContext owner = new LlmCallContext(7L, UUID.randomUUID(), UUID.randomUUID(), CallPurpose.AGENT, true);
    private final LlmClient.LlmRequest request = new LlmClient.LlmRequest("fake", "model", List.of(), List.of(), 0).withMaxTokens(30);
    private final UUID logical = UUID.randomUUID(), attempt = UUID.randomUUID();
    TokenReservationTest(){var bound=new AgentProperties.AccountingBound();bound.setInputTokens(70);bound.setOutputTokens(30);bound.setEvidence("Controlled fixture");props.getAccountingBounds().put("fake:model",bound);}

    @Test void passesOwnerCapsAndIdsToTheDatabaseAuthority() {
        props.getLimits().setDailyTokenQuota(100);
        when(budget.reserve(any())).thenReturn(new DailyTokenBudget.Grant(attempt, 7L, LocalDate.of(2026,10,3),100,true,null));
        assertTrue(policy.reserve(owner, request,70,logical,attempt).granted());
        verify(budget).reserve(new DailyTokenBudget.Request(attempt,logical,owner,"fake","model",70,30,100));
        verifyNoInteractions(redis);
    }
    @Test void databaseRefusalNeverUsesARedisFallback() {
        when(budget.reserve(any())).thenReturn(new DailyTokenBudget.Grant(attempt,7L,LocalDate.of(2026,10,3),100,false,AgentUsagePolicy.QUOTA_LOW_MESSAGE));
        assertFalse(policy.reserve(owner,request,70,logical,attempt).granted());
        verifyNoInteractions(redis);
    }
    @Test void databaseOutageFailsClosed() {
        when(budget.reserve(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Scripted DB outage"));
        assertThrows(LlmAccountingException.class, () -> policy.reserve(owner,request,70,logical,attempt));
        verifyNoInteractions(redis);
    }
    @Test void unlimitedQuotaStillRequiresAccountingOwnership() {
        props.getLimits().setDailyTokenQuota(0);
        when(budget.reserve(any())).thenReturn(new DailyTokenBudget.Grant(attempt,7L,LocalDate.of(2026,10,3),100,true,null));
        policy.reserve(owner,request,70,logical,attempt);
        verify(budget).reserve(argThat(r -> r.quota()==0 && r.context().userId().equals(7L)));
    }
    @Test void settlementIsDelegatedWithoutAReleaseWindow() {
        var report = new LlmClient.Usage(12,3);
        policy.settle(attempt,report);
        verify(budget).settleReported(attempt,report);
        verify(budget,never()).releaseUnsent(any());
    }
    @Test void unknownAndUnsentHaveDifferentOperations() {
        policy.retainUnknown(attempt);
        policy.releaseUnsent(logical);
        verify(budget).retainUnknown(attempt);
        verify(budget).releaseUnsent(logical);
    }
}
