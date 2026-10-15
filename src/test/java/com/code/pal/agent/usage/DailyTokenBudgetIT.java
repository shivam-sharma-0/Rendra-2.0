package com.code.pal.agent.usage;

import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyTokenBudgetIT {
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private DailyTokenBudget budget;
    private MutableClock clock;
    private long user;
    @BeforeAll void open() {
        context = CodexJpaFixture.open(Services.class);
        budget = context.getBean(DailyTokenBudget.class);
        clock = context.getBean(MutableClock.class);
    }
    @AfterAll void close() { if (context != null) context.close(); }
    @BeforeEach void reset() { clock.time.set(Instant.parse("2026-10-10T23:59:59Z")); user = 1000 + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000); }
    private DailyTokenBudget.Request request(long amount, long quota) {
        return new DailyTokenBudget.Request(UUID.randomUUID(), UUID.randomUUID(),
                new LlmCallContext(user, UUID.randomUUID(), UUID.randomUUID(), CallPurpose.AGENT, true), "fake", "model", amount - 10, 10, quota);
    }
    private DailyTokenBudget.Usage usage() { return budget.usage(user, LocalDate.of(2026,10,10)); }

    @Test void concurrentReservesCannotBothSpendTheSameCapacity() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<DailyTokenBudget.Grant> reserve = () -> { assertTrue(start.await(3, TimeUnit.SECONDS)); return budget.reserve(request(60,100)); };
            var a = pool.submit(reserve); var b = pool.submit(reserve); start.countDown();
            var first = a.get(5, TimeUnit.SECONDS); var second = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(first.granted(), second.granted());
            assertEquals(60, usage().reserved());
            assertEquals(60, usage().committedAndReserved());
        }
    }

    @Test void settlementAndNewReservationHaveNoCapacityGap() throws Exception {
        var first = budget.reserve(request(80,100)); budget.markSent(first.id());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var settle = pool.submit(() -> { start.await(); budget.settleReported(first.id(), new LlmClient.Usage(15,5)); return null; });
            var reserve = pool.submit(() -> { start.await(); return budget.reserve(request(30,100)); });
            start.countDown(); settle.get(5, TimeUnit.SECONDS);
            var next = reserve.get(5, TimeUnit.SECONDS);
            assertEquals(20, usage().known());
            assertEquals(next.granted() ? 30 : 0, usage().reserved());
            assertTrue(usage().committedAndReserved() <= 100);
            assertFalse(budget.reserve(request(100,100)).granted());
        }
    }

    @Test void unknownUsageRemainsChargedAndReconciliationIsIdempotent() {
        var grant = budget.reserve(request(80,100)); budget.markSent(grant.id());
        budget.retainUnknown(grant.id()); budget.retainUnknown(grant.id());
        assertEquals(80, usage().charged()); assertEquals(0, usage().reserved()); assertEquals(1, usage().unknownCalls());
        assertFalse(budget.reserve(request(30,100)).granted());
        budget.settleReported(grant.id(), new LlmClient.Usage(15,5));
        budget.settleReported(grant.id(), new LlmClient.Usage(15,5));
        assertEquals(20, usage().charged()); assertEquals(0, usage().unknown()); assertEquals(0, usage().unknownCalls());
        assertThrows(IllegalStateException.class, () -> budget.settleReported(grant.id(), new LlmClient.Usage(1,1)));
    }

    @Test void releasedUnsentAttemptCannotResumeAndSentAttemptCannotRefund() {
        var unsent = budget.reserve(request(60,100)); budget.releaseUnsent(unsent.id()); budget.releaseUnsent(unsent.id());
        assertEquals(0, usage().committedAndReserved());
        assertThrows(IllegalStateException.class, () -> budget.markSent(unsent.id()));
        var sent = budget.reserve(request(60,100)); budget.markSent(sent.id());
        assertThrows(IllegalStateException.class, () -> budget.releaseUnsent(sent.id()));
        assertEquals(60, usage().reserved());
    }

    @Test void attemptIdentityAndTransmissionAreIdempotent() {
        var request = request(60,100);
        budget.reserve(request); budget.reserve(request);
        assertEquals(60, usage().reserved());
        assertThrows(IllegalStateException.class, () -> budget.reserve(new DailyTokenBudget.Request(request.id(), request.logicalCallId(),
                request.context(), "different", "model", request.inputBound(), 10, 100)));
        budget.markSent(request.id());
        assertThrows(IllegalStateException.class, () -> budget.markSent(request.id()));
    }

    @Test void settlementKeepsTheSendDayAfterMidnightAndUnknownNeverExpires() {
        var grant = budget.reserve(request(60,100)); budget.markSent(grant.id());
        clock.time.set(Instant.parse("2026-10-11T00:00:01Z"));
        budget.retainUnknown(grant.id());
        assertEquals(60, usage().unknown());
        assertEquals(0, budget.usage(user, LocalDate.of(2026,10,11)).charged());
        clock.time.set(Instant.parse("2026-11-11T00:00:01Z"));
        assertEquals(60, usage().unknown());
        budget.settleReported(grant.id(), new LlmClient.Usage(12,3));
        assertEquals(15, usage().charged());
    }

    @Test void databaseFailureDoesNotGrantCapacity() {
        var jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("alter table ai_token_attempts rename to ai_token_attempts_temporarily_unavailable");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> budget.reserve(request(60,100))); }
        finally { jdbc.execute("alter table ai_token_attempts_temporarily_unavailable rename to ai_token_attempts"); }
        assertEquals(0, usage().committedAndReserved());
    }

    @Test void primaryGatewayAndAdmissionUseDbWhenRedisIsUnavailable() {
        var redis = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Scripted Redis outage"));
        var policy = context.getBean(AgentUsagePolicy.class);
        assertDoesNotThrow(() -> policy.requireAdmitted(user));
        var script = context.getBean(com.code.pal.testsupport.ScriptedLlmClient.class);
        script.answer("done", new LlmClient.Usage(3,2));
        var call = new LlmClient.LlmRequest("fake","model",java.util.List.of(LlmClient.LlmMessage.user("hi")),java.util.List.of(),0).withMaxTokens(10);
        try (var ignored = LlmCallContext.bind(new LlmCallContext(user,UUID.randomUUID(),UUID.randomUUID(),CallPurpose.AGENT,true))) {
            assertEquals("done",context.getBean(MeteredLlmClient.class).chat(call).content());
        }
        assertEquals(5,usage().known()); assertEquals(0,usage().reserved());
        assertEquals(1,context.getBean(JdbcTemplate.class).queryForObject("select count(*) from agent_model_calls where user_id=? and attempt_id is not null",Integer.class,user));
    }

    @Test void failedLedgerWriteRetainsCapacityAfterTheProviderAnswered() {
        var jdbc = context.getBean(JdbcTemplate.class);
        var script = context.getBean(com.code.pal.testsupport.ScriptedLlmClient.class);
        script.answer("done",new LlmClient.Usage(3,2));
        var call = new LlmClient.LlmRequest("fake","model",java.util.List.of(LlmClient.LlmMessage.user("hi")),java.util.List.of(),0).withMaxTokens(10);
        jdbc.execute("alter table agent_model_calls rename to agent_model_calls_temporarily_unavailable");
        try (var ignored = LlmCallContext.bind(new LlmCallContext(user,UUID.randomUUID(),UUID.randomUUID(),CallPurpose.AGENT,true))) {
            assertThrows(com.code.pal.agent.llm.LlmAccountingException.class, () -> context.getBean(MeteredLlmClient.class).chat(call));
        } finally { jdbc.execute("alter table agent_model_calls_temporarily_unavailable rename to agent_model_calls"); }
        assertTrue(usage().unknown()>0); assertEquals(1,usage().unknownCalls()); assertEquals(0,usage().reserved());
    }

    @Test void midnightBeforeTransmissionMovesTheReservationToTheActualSendDay() {
        var grant=budget.reserve(request(60,100));
        clock.time.set(Instant.parse("2026-10-11T00:00:01Z")); budget.markSent(grant.id());
        assertEquals(0,usage().reserved());assertEquals(60,budget.usage(user,LocalDate.of(2026,10,11)).reserved());
        clock.time.set(Instant.parse("2026-10-12T00:00:01Z"));budget.finishAttempt(grant.id(),new LlmClient.Usage(12,3),"OK",null,1);
        assertEquals(15,budget.usage(user,LocalDate.of(2026,10,11)).known());assertEquals(0,usage().charged());
    }
    @Test void midnightCannotBorrowCapacityFromThePreviousDay() {
        var prior=budget.reserve(request(60,100));
        clock.time.set(Instant.parse("2026-10-11T00:00:01Z"));var current=budget.reserve(request(60,100));budget.markSent(current.id());budget.retainUnknown(current.id());
        assertThrows(com.code.pal.agent.llm.LlmQuotaExceededException.class,()->budget.markSent(prior.id()));
        budget.releaseUnsent(prior.id());assertEquals(0,usage().reserved());assertEquals(60,budget.usage(user,LocalDate.of(2026,10,11)).charged());
    }
    @Test void crashRecoveryReleasesOnlyUnsentAndReplaysUnknownWithoutLosingLateUsage() {
        var unsent=budget.reserve(request(60,1000));var sent=budget.reserve(request(60,1000));budget.markSent(sent.id());
        clock.time.set(Instant.parse("2026-10-11T00:10:00Z"));Instant cutoff=clock.instant().minusSeconds(60);
        assertTrue(budget.pendingRecovery(cutoff,100).containsAll(java.util.List.of(unsent.id(),sent.id())));
        budget.recover(unsent.id(),cutoff);budget.recover(sent.id(),cutoff);budget.recover(sent.id(),cutoff);
        assertEquals(60,usage().charged());assertEquals(0,usage().reserved());assertEquals(1,usage().unknownCalls());
        assertThrows(IllegalStateException.class,()->budget.markSent(unsent.id()));
        budget.finishAttempt(sent.id(),new LlmClient.Usage(12,3),"OK",null,10);
        assertEquals(15,usage().known());assertEquals(0,usage().unknown());
        assertEquals("OK",context.getBean(JdbcTemplate.class).queryForObject("select outcome from agent_model_calls where attempt_id=?",String.class,sent.id()));
        assertEquals(1,context.getBean(JdbcTemplate.class).queryForObject("select count(*) from agent_model_calls where attempt_id=?",Integer.class,sent.id()));
    }
    @Test void recoveryRepairsMissingLedgerAndDoesNotDowngradeKnownUsage() {
        var grant=budget.reserve(request(60,100));budget.markSent(grant.id());budget.settleReported(grant.id(),new LlmClient.Usage(12,3));
        budget.recover(grant.id(),clock.instant().minusSeconds(60));budget.recover(grant.id(),clock.instant().minusSeconds(60));
        assertEquals(15,usage().known());assertEquals(0,usage().unknownCalls());
        assertEquals(true,context.getBean(JdbcTemplate.class).queryForObject("select usage_known from agent_model_calls where attempt_id=?",Boolean.class,grant.id()));
    }
    @Test void runBudgetIncludesAllItsAttemptsAcrossUtcPeriodsAndCannotBeRaised() {
        var first=request(60,1000);budget.configureRun(first.context().runId(),user,100);budget.reserve(first);budget.markSent(first.id());budget.retainUnknown(first.id());
        clock.time.set(Instant.parse("2026-10-11T00:00:01Z"));
        var next=new DailyTokenBudget.Request(UUID.randomUUID(),UUID.randomUUID(),first.context(),"fake","model",50,10,1000);
        assertFalse(budget.reserve(next).granted());assertThrows(IllegalStateException.class,()->budget.configureRun(first.context().runId(),user,200));
        budget.settleReported(first.id(),new LlmClient.Usage(12,3));assertTrue(budget.reserve(next).granted());
    }
    @Test void providerBoundViolationIsFullyChargedAndSuspendsNewRequests() {
        var first=request(60,1000);var isolated=new DailyTokenBudget.Request(first.id(),first.logicalCallId(),first.context(),"violating-fixture","model",50,10,1000);
        budget.reserve(isolated);budget.markSent(first.id());budget.finishAttempt(first.id(),new LlmClient.Usage(100,20),"OK",null,1);
        assertEquals(120,usage().known());assertTrue(budget.boundExceeded(first.id()));
        var next=new DailyTokenBudget.Request(UUID.randomUUID(),UUID.randomUUID(),first.context(),"violating-fixture","model",50,10,1000);
        assertThrows(IllegalStateException.class,()->budget.reserve(next));
    }
    @Test void estimatedReservationCanSettleHigherActualUsageWithoutSuspendingTheModel(){
        var first=request(60,1000);var estimated=new DailyTokenBudget.Request(first.id(),first.logicalCallId(),first.context(),"estimated-fixture","model",20,10,1000,1000);
        budget.reserve(estimated);budget.markSent(first.id());budget.finishAttempt(first.id(),new LlmClient.Usage(100,5),"OK",null,1);
        assertEquals(105,usage().known());assertEquals(0,usage().reserved());assertFalse(budget.boundExceeded(first.id()));
    }
    @Test void cancelledInputEstimateAndLateUsageReconcileOnceInDailyRunAndProjectTotals(){
        var first=request(60,1000);budget.reserve(first);budget.markSent(first.id());budget.finishAttempt(first.id(),null,"CANCELLED",null,1);
        assertEquals(50,usage().unknown());assertEquals(50,budget.projectUsage(first.context().projectId()).unknownTokens());assertEquals(0,usage().reserved());
        budget.retainUnknown(first.id());assertEquals(50,usage().unknown());budget.settleReported(first.id(),new LlmClient.Usage(12,3));budget.settleReported(first.id(),new LlmClient.Usage(12,3));
        assertEquals(15,usage().known());assertEquals(0,usage().unknown());assertEquals(0,budget.projectUsage(first.context().projectId()).unknownTokens());
    }
    @Test void projectAnalyticsCountRetriesUnknownReservationsAndLegacyOnce() {
        var first=request(60,1000);var ctx=first.context();budget.reserve(first);budget.markSent(first.id());budget.finishAttempt(first.id(),new LlmClient.Usage(12,3),"OK",null,1);
        var retry=new DailyTokenBudget.Request(UUID.randomUUID(),first.logicalCallId(),ctx,"fake","model",50,10,1000);
        budget.reserve(retry);budget.markSent(retry.id());budget.finishAttempt(retry.id(),null,"ERROR",null,1);
        var active=new DailyTokenBudget.Request(UUID.randomUUID(),UUID.randomUUID(),ctx,"fake","model",30,10,1000);budget.reserve(active);
        var released=new DailyTokenBudget.Request(UUID.randomUUID(),UUID.randomUUID(),ctx,"fake","model",20,10,1000);budget.reserve(released);budget.releaseUnsent(released.id());
        var jdbc=context.getBean(JdbcTemplate.class);
        jdbc.update("insert into ai_legacy_usage(source_key,user_id,project_id,period_start,known_tokens,unknown_tokens,unknown_calls) values (?,?,?,date '2026-10-01',7,0,0)","call:"+UUID.randomUUID(),user,ctx.projectId());
        var result=budget.projectUsage(ctx.projectId());
        assertEquals(22,result.reportedTokens());assertEquals(60,result.unknownTokens());assertEquals(40,result.reservedTokens());
        assertEquals(82,result.chargedTokens());assertEquals(1,result.unknownCalls());assertEquals(2,result.actualAttempts());assertEquals(1,result.logicalCalls());assertEquals(1,result.historicalAttemptRecords());
        assertEquals(result,budget.projectUsage(ctx.projectId()));assertEquals(0,budget.projectUsage(UUID.randomUUID()).chargedTokens());
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-10T23:59:59Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    }
    @Configuration static class Services {
        @Bean ProviderScheduler scheduler(){return org.mockito.Mockito.mock(ProviderScheduler.class);}
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean DailyTokenBudget budget(JdbcTemplate jdbc, MutableClock clock) { return new DailyTokenBudget(jdbc, clock); }
        @Bean com.code.pal.agent.config.AgentProperties properties() { var props=new com.code.pal.agent.config.AgentProperties(); var bound=new com.code.pal.agent.config.AgentProperties.AccountingBound();bound.setInputTokens(20);bound.setOutputTokens(10);bound.setEvidence("Controlled fixture only");props.getAccountingBounds().put("fake:model",bound);return props; }
        @Bean org.springframework.data.redis.core.StringRedisTemplate redis() { return org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class); }
        @Bean AgentUsagePolicy policy(com.code.pal.agent.config.AgentProperties p, com.code.pal.agent.repository.AgentRunRepository r,
                                    org.springframework.data.redis.core.StringRedisTemplate redis, MutableClock clock, DailyTokenBudget b) {
            return new AgentUsagePolicy(p,r,redis,clock,b);
        }
        @Bean com.code.pal.testsupport.ScriptedLlmClient script() { return new com.code.pal.testsupport.ScriptedLlmClient(); }
        @Bean MeteredLlmClient client(com.code.pal.testsupport.ScriptedLlmClient script,
                                    AgentUsagePolicy policy, com.code.pal.agent.config.AgentProperties p) {
            return new MeteredLlmClient(script,policy,p);
        }
    }
}
