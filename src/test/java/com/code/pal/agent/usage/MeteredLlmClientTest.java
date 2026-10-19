package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeteredLlmClientTest {
    private final AgentProperties props = new AgentProperties();
    private final AgentUsagePolicy policy = mock(AgentUsagePolicy.class);
    private record Finished(UUID id, LlmClient.Usage report, String outcome, String error) {}
    private final List<Finished> saved = new ArrayList<>();
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final LlmCallContext owner = new LlmCallContext(7L,UUID.randomUUID(),UUID.randomUUID(),CallPurpose.AGENT,true);
    private final LlmClient.LlmRequest request = new LlmClient.LlmRequest("fake","model",List.of(LlmClient.LlmMessage.user("hello")),List.of(),0).withMaxTokens(100);
    private final LlmClient.LlmResult ok = new LlmClient.LlmResult("hi",null,List.of(),new LlmClient.Usage(12,3),"stop");

    MeteredLlmClientTest() {
        var bound=new AgentProperties.AccountingBound(); bound.setInputTokens(200); bound.setOutputTokens(100); bound.setEvidence("Controlled test fixture only"); props.getAccountingBounds().put("fake:model",bound);
        when(policy.reserve(any(),any(),anyLong(),any(),any())).thenAnswer(call -> new DailyTokenBudget.Grant(
                call.getArgument(4),7L,LocalDate.of(2026,10,10),100,true,null));
        doAnswer(call -> { saved.add(new Finished(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3))); return null; })
                .when(policy).finishAttempt(any(),nullable(LlmClient.Usage.class),anyString(),nullable(String.class),anyInt());
    }
    private MeteredLlmClient client(LlmClient.LlmResult result, RuntimeException failure) {
        LlmClient fake = (request, delta) -> {
            providerCalls.incrementAndGet();
            if(failure!=null) throw failure;
            return result;
        };
        return new MeteredLlmClient(fake,policy,props);
    }
    @Test void runCallsAlsoReserveAndSettleThroughTheSameAuthority() {
        try(var ignored=LlmCallContext.bind(owner)) { assertEquals("hi",client(ok,null).chat(request).content()); }
        assertEquals(1,providerCalls.get());
        assertEquals(12,saved.getFirst().report().promptTokens());
        assertNotNull(saved.getFirst().id());
        verify(policy).reserve(eq(owner),eq(request),anyLong(),notNull(),eq(saved.getFirst().id()));
        assertEquals("OK",saved.getFirst().outcome());
        verify(policy,never()).releaseUnsent(any());
    }
    @Test void absentUsageRetainsUnknownCapacity() {
        var unknown=new LlmClient.LlmResult("hi",null,List.of(),null,"stop");
        try(var ignored=LlmCallContext.bind(owner)) { client(unknown,null).chat(request); }
        assertNull(saved.getFirst().report());
        assertEquals("OK",saved.getFirst().outcome());
        verify(policy,never()).releaseUnsent(any());
    }
    @Test void rejectedRequestsChargeZeroWithoutReleasingPossiblySentIdentity(){
        try(var ignored=LlmCallContext.bind(owner)){assertThrows(LlmHttpException.class,()->client(null,new LlmHttpException(401,"rejected")).chat(request));}
        assertEquals(new LlmClient.Usage(0,0),saved.getFirst().report());assertEquals("FAILED",saved.getFirst().outcome());verify(policy,never()).releaseUnsent(any());
    }
    @Test void failureAndCancellationRetainPossiblyBilledCapacity() {
        try(var ignored=LlmCallContext.bind(owner)) {
            assertThrows(LlmHttpException.class,()->client(null,new LlmHttpException(503,"Scripted provider failure")).chat(request));
            assertThrows(LlmCancelledException.class,()->client(null,new LlmCancelledException()).chat(request));
        }
        assertEquals("FAILED",saved.get(0).outcome());
        assertEquals("HTTP 503",saved.get(0).error());
        assertEquals("CANCELLED",saved.get(1).outcome());
        assertTrue(saved.stream().allMatch(call -> call.report()==null));
        verify(policy,never()).releaseUnsent(any());
    }
    @Test void streamErrorCodesAreNotTreatedAsUnbilledHttpRejections() {
        try (var ignored = LlmCallContext.bind(owner)) {
            assertThrows(LlmStreamException.class, () -> client(null,
                    new LlmStreamException(LlmStreamException.Reason.PROVIDER_ERROR, 404, null)).chat(request));
            assertThrows(LlmStreamException.class, () -> client(null,
                    new LlmStreamException(LlmStreamException.Reason.PROVIDER_ERROR, 503, new LlmClient.Usage(12, 3))).chat(request));
        }
        assertNull(saved.get(0).report(), "HTTP-200 stream errors may already have consumed tokens");
        assertEquals("STREAM PROVIDER_ERROR 404", saved.get(0).error());
        assertEquals(new LlmClient.Usage(12, 3), saved.get(1).report());
        assertEquals("FAILED", saved.get(1).outcome());
        verify(policy, never()).releaseUnsent(any());
    }

    @Test void partialStreamFailoverKeepsTheUnknownAttemptAndAccountsForTheFallbackSeparately() {
        props.getProviders().put("fake", new AgentProperties.Provider());
        props.getModels().setPlan(List.of("fake:model", "fake:fallback"));
        props.getAccountingBounds().put("fake:fallback", props.getAccountingBounds().get("fake:model"));
        var metered = new MeteredLlmClient((routed, delta) -> {
            if (providerCalls.incrementAndGet() == 1)
                throw new LlmStreamException(LlmStreamException.Reason.INTERRUPTED_STREAM, null, null);
            return ok;
        }, policy, props);
        try (var ignored = LlmCallContext.bind(owner)) {
            assertSame(ok, new com.code.pal.agent.plan.PlanningModelCalls(props).execute(
                    new LlmClient.LlmRequest("fake", "model", request.messages(), List.of(), 0,
                            java.time.Duration.ofMinutes(3)).withMaxTokens(100), metered::chat, () -> {}));
        }
        assertEquals(2, saved.size());
        assertNull(saved.getFirst().report());
        assertEquals("FAILED", saved.getFirst().outcome());
        assertEquals("STREAM INTERRUPTED_STREAM", saved.getFirst().error());
        assertEquals(ok.usage(), saved.getLast().report());
        assertNotEquals(saved.getFirst().id(), saved.getLast().id());
        verify(policy, times(2)).reserve(any(), any(), anyLong(), any(), any());
        verify(policy, never()).releaseUnsent(any());
    }
    @Test void brokenLedgerFailsTheCallAndRetainsItsBound() {
        doThrow(new IllegalStateException("Scripted DB failure")).when(policy).finishAttempt(any(),any(),anyString(),nullable(String.class),anyInt());
        try(var ignored=LlmCallContext.bind(owner)) { assertThrows(LlmAccountingException.class,()->client(ok,null).chat(request)); }
        assertEquals(1,providerCalls.get());
        verify(policy).retainUnknown(any());
        verify(policy,never()).settle(any(),any());
    }
    @Test void refusalOrStorageFailureSendsNoRequest() {
        try(var ignored=LlmCallContext.bind(owner)) {
            when(policy.reserve(any(),any(),anyLong(),any(),any())).thenReturn(new DailyTokenBudget.Grant(UUID.randomUUID(),7L,LocalDate.of(2026,10,10),100,false,"Quota refused"));
            assertThrows(LlmQuotaExceededException.class,()->client(ok,null).chat(request));
            when(policy.reserve(any(),any(),anyLong(),any(),any())).thenThrow(new LlmAccountingException("Accounting unavailable"));
            assertThrows(LlmAccountingException.class,()->client(ok,null).chat(request));
        }
        assertEquals(0,providerCalls.get()); assertTrue(saved.isEmpty());
    }
    @Test void ownerlessCallCannotBypassAccounting() {
        assertThrows(LlmAccountingException.class,()->client(ok,null).chat(request));
        assertEquals(0,providerCalls.get()); verifyNoInteractions(policy);
    }
    @Test void schedulingRefusalSendsNoRequestOrBudgetReservation() {
        var scheduler=mock(ProviderScheduler.class);var client=client(ok,null);client.useScheduler(scheduler);
        when(scheduler.acquire(any(),any())).thenThrow(new LlmAccountingException("Account capacity unavailable"));
        try(var ignored=LlmCallContext.bind(owner)){assertThrows(LlmAccountingException.class,()->client.chat(request));}
        assertEquals(0,providerCalls.get());verifyNoInteractions(policy);
    }
    @Test void aCompletedPhysicalAttemptReleasesItsPermitEvenIfAccountingFails() {
        var scheduler=mock(ProviderScheduler.class);var permit=mock(ProviderScheduler.Permit.class);var client=client(ok,null);client.useScheduler(scheduler);
        when(scheduler.acquire(any(),any())).thenReturn(permit);
        doThrow(new IllegalStateException("Fixture settle failure")).when(policy).finishAttempt(any(),any(),anyString(),nullable(String.class),anyInt());
        try(var ignored=LlmCallContext.bind(owner)){assertThrows(LlmAccountingException.class,()->client.chat(request));}
        verify(permit).sent();verify(permit).completed();verify(permit).close();verify(policy).retainUnknown(any());
    }
    @Test void cancellationBeforeTransmissionNeedsNoReservation() {
        var cancellation=new RunCancellation(UUID.randomUUID()); cancellation.cancel();
        try(var ignored=LlmCallContext.bind(owner);var bound=RunCancellation.bind(cancellation)) {
            assertThrows(LlmCancelledException.class,()->client(ok,null).chat(request));
        }
        assertEquals(0,providerCalls.get()); verifyNoInteractions(policy);
    }
}
