package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.testsupport.CodexJpaFixture;
import com.code.pal.testsupport.LocalStreamingProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActualProviderAttemptsIT {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AgentProperties props;
    private DailyTokenBudget budget;
    private long user;
    @BeforeAll void open() {
        context=CodexJpaFixture.open(Services.class); jdbc=context.getBean(JdbcTemplate.class);
        props=context.getBean(AgentProperties.class); budget=context.getBean(DailyTokenBudget.class);
    }
    @AfterAll void close() { if(context!=null) context.close(); }
    @BeforeEach void reset() {
        user=1000+Math.abs(UUID.randomUUID().getLeastSignificantBits()%1_000_000_000);
        props.getAccountingBounds().clear();
        for(String model:List.of("model","failed-model","fallback-model")) {
            var bound=new AgentProperties.AccountingBound(); bound.setInputTokens(20);bound.setOutputTokens(10);bound.setEvidence("Controlled HTTP fixture only");props.getAccountingBounds().put("openrouter:"+model,bound);
        }
        props.getLimits().setDailyTokenQuota(10000); props.getProviders().clear();
        props.getLlm().setMaxAttempts(2); props.getLlm().setRetryInitialBackoffMs(1); props.getLlm().setRetryMaxBackoffMs(1);
    }
    private LlmClient.LlmRequest request(String model) {
        return new LlmClient.LlmRequest("openrouter",model,List.of(LlmClient.LlmMessage.user("hello")),List.of(),0).withMaxTokens(10);
    }
    private LlmCallContext owner() { return new LlmCallContext(user,UUID.randomUUID(),UUID.randomUUID(),CallPurpose.AGENT,true); }
    private OpenAiCompatibleLlmClient transport(LocalStreamingProvider provider) {
        props.getLlm().setBaseUrl(provider.baseUrl()); props.getLlm().setApiKey("fixture-only");
        var transport=new OpenAiCompatibleLlmClient(props,new ObjectMapper()); transport.requireAccountingForManagedUse(); return transport;
    }
    private MeteredLlmClient gateway(LocalStreamingProvider provider) {
        return new MeteredLlmClient(transport(provider),context.getBean(AgentUsagePolicy.class),props);
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,user); }
    private DailyTokenBudget.Usage usage() { return budget.usage(user,LocalDate.of(2026,10,10)); }

    @Test void httpRetryHasTwoDurableAttemptsUnderOneLogicalCall() throws Exception {
        try(var provider=new LocalStreamingProvider().error(429).answer(3,2); var bound=LlmCallContext.bind(owner())) {
            assertEquals("done",gateway(provider).chat(request("model")).content()); provider.assertConsumed();
            assertEquals(2,provider.requests()); assertEquals(2,count("ai_token_attempts")); assertEquals(2,count("agent_model_calls"));
            assertEquals(1,jdbc.queryForObject("select count(distinct logical_call_id) from agent_model_calls where user_id=?",Integer.class,user));
            assertEquals(0,usage().unknownCalls()); assertEquals(5,usage().known()); assertEquals(0,usage().reserved());
            assertEquals(0,jdbc.queryForObject("select count(*) from agent_model_calls where user_id=? and usage_known=false",Integer.class,user));
        }
    }
    @Test void rejectedKeyRotationAlsoHasTwoAttempts() throws Exception {
        try(var provider=new LocalStreamingProvider().error(401).answer(2,1); var bound=LlmCallContext.bind(owner())) {
            var configured=new AgentProperties.Provider(); configured.setBaseUrl(provider.baseUrl()); configured.setApiKeys(List.of("fixture-a","fixture-b"));
            props.getProviders().put("openrouter",configured);
            assertEquals("done",gateway(provider).chat(request("model")).content()); provider.assertConsumed();
            assertEquals(2,provider.requests()); assertEquals(2,count("agent_model_calls")); assertEquals(0,usage().unknownCalls());assertEquals(3,usage().known());
        }
    }
    @Test void quotaRefusesRetryBeforeItsSecondHttpTransmission() throws Exception {
        props.getLimits().setDailyTokenQuota(30);
        try(var provider=new LocalStreamingProvider().error(503); var bound=LlmCallContext.bind(owner())) {
            assertThrows(LlmQuotaExceededException.class,()->gateway(provider).chat(request("model")));
            assertEquals(1,provider.requests()); assertEquals(1,count("ai_token_attempts")); assertEquals(1,count("agent_model_calls"));
            assertEquals(1,usage().unknownCalls()); assertEquals(props.getLimits().getDailyTokenQuota(),usage().charged());
        }
    }
    @Test void fallbackCallsHaveSeparateLogicalIdsAndKeepFailedCapacity() throws Exception {
        props.getLlm().setMaxAttempts(1);
        try(var provider=new LocalStreamingProvider().error(503).answer(3,2); var bound=LlmCallContext.bind(owner())) {
            var client=gateway(provider);
            assertThrows(LlmException.class,()->client.chat(request("failed-model")));
            client.chat(request("fallback-model"));
            assertEquals(2,provider.requests()); assertEquals(2,count("agent_model_calls"));
            assertEquals(2,jdbc.queryForObject("select count(distinct logical_call_id) from agent_model_calls where user_id=?",Integer.class,user));
            assertEquals(1,usage().unknownCalls()); assertEquals(5,usage().known());
        }
    }
    @Test void absentUsageAndReportedZeroStayDistinct() throws Exception {
        try(var provider=new LocalStreamingProvider().answer(null,null).answer(0,0);var bound=LlmCallContext.bind(owner())) {
            var client=gateway(provider); assertNull(client.chat(request("model")).usage());
            assertEquals(new LlmClient.Usage(0,0),client.chat(request("model")).usage());
            assertEquals(1,usage().unknownCalls()); assertEquals(0,usage().known());
            assertEquals(1,jdbc.queryForObject("select count(*) from agent_model_calls where user_id=? and usage_known=true",Integer.class,user));
        }
    }
    @Test void managedTransportCannotBypassPrimaryAccounting() throws Exception {
        try(var provider=new LocalStreamingProvider()) {
            assertThrows(LlmAccountingException.class,()->transport(provider).chat(request("model")));
            assertEquals(0,provider.requests()); assertEquals(0,count("ai_token_attempts"));
        }
    }
    @Test void ledgerFailureRollsBackKnownSettlementButKeepsPossibleSpend() throws Exception {
        try(var provider=new LocalStreamingProvider().answer(3,2);var bound=LlmCallContext.bind(owner())) {
            jdbc.execute("alter table agent_model_calls rename to agent_model_calls_missing");
            try { assertThrows(LlmAccountingException.class,()->gateway(provider).chat(request("model"))); }
            finally { jdbc.execute("alter table agent_model_calls_missing rename to agent_model_calls"); }
            assertEquals(1,provider.requests()); assertEquals(0,count("agent_model_calls"));
            assertEquals(0,usage().known()); assertEquals(1,usage().unknownCalls()); assertTrue(usage().charged()>0);
        }
    }
    @Test void cancellationAfterHttpTransmissionKeepsUnknownUsageAndSendsNoRetry() throws Exception {
        var cancellation=new com.code.pal.agent.runtime.RunCancellation(UUID.randomUUID());var owner=owner();
        try(var provider=new LocalStreamingProvider().waitForCancellation();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var client=gateway(provider);
            var future=pool.submit(()-> {
                try(var bound=LlmCallContext.bind(owner);var stop=com.code.pal.agent.runtime.RunCancellation.bind(cancellation)) {
                    return client.chat(request("model"));
                }
            });
            assertTrue(provider.awaitRequest());cancellation.cancel();
            var failed=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(LlmCancelledException.class,failed.getCause());
            assertEquals(1,provider.requests());assertEquals(1,count("agent_model_calls"));assertEquals(1,usage().unknownCalls());
            assertEquals("CANCELLED",jdbc.queryForObject("select outcome from agent_model_calls where user_id=?",String.class,user));
        }
    }
    @Test void unverifiedModelIsDeniedBeforeHttpAndBeforeReservation() throws Exception {
        props.getAccountingBounds().clear();
        try(var provider=new LocalStreamingProvider();var bound=LlmCallContext.bind(owner())) {
            assertThrows(LlmAccountingException.class,()->gateway(provider).chat(request("model")));
            assertEquals(0,provider.requests());assertEquals(0,count("ai_token_attempts"));
        }
    }
    @Configuration static class Services {
        @Bean AgentProperties props() { return new AgentProperties(); }
        @Bean DailyTokenBudget budget(JdbcTemplate jdbc) { return new DailyTokenBudget(jdbc,Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),ZoneOffset.UTC)); }
        @Bean org.springframework.data.redis.core.StringRedisTemplate redis() { return org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class); }
        @Bean AgentUsagePolicy policy(AgentProperties p,com.code.pal.agent.repository.AgentRunRepository r,
                                     org.springframework.data.redis.core.StringRedisTemplate redis,DailyTokenBudget b) {
            return new AgentUsagePolicy(p,r,redis,Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),ZoneOffset.UTC),b);
        }
    }
}
