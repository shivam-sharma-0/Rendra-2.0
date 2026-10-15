package com.code.pal.agent.usage;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderSchedulerIT {
    AnnotationConfigApplicationContext app;ProviderScheduler scheduler;JdbcTemplate jdbc;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);scheduler=app.getBean(ProviderScheduler.class);jdbc=app.getBean(JdbcTemplate.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    @BeforeEach void clear(){jdbc.update("delete from agent_provider_slots");jdbc.update("delete from agent_scheduler_user_turns");}
    LlmCallContext user(long id){return new LlmCallContext(id,null,null,CallPurpose.SMALL_TALK,false);}
    LlmClient.LlmRequest request(String provider){return new LlmClient.LlmRequest(provider,"fake",List.of(),List.of(),0,Duration.ofSeconds(2));}
    @Test void blockedHeavyUserDoesNotPreventAnotherUsersSmallCallAndCancelledWaiterNeverStarts() throws Exception {
        try(var first=scheduler.acquire(user(7),request("fake"));var threads=Executors.newVirtualThreadPerTaskExecutor()){
            var stop=new RunCancellation(UUID.randomUUID());var waiting=threads.submit(()->{try(var bound=RunCancellation.bind(stop)){return scheduler.acquire(user(7),request("alias"));}});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(jdbc.queryForObject("select count(*) from agent_provider_slots where state='WAITING'",Integer.class)==0 && System.nanoTime()<end)Thread.sleep(10);
            assertEquals(1,jdbc.queryForObject("select count(*) from agent_provider_slots where state='WAITING'",Integer.class));
            try(var small=scheduler.acquire(user(8),request("alias"))){assertEquals(2,jdbc.queryForObject("select count(*) from agent_provider_slots where state='ACTIVE'",Integer.class));}
            stop.cancel();assertThrows(ExecutionException.class,()->waiting.get(2,TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("select count(*) from agent_provider_slots where state='WAITING'",Integer.class));
        }
    }
    @Test void accountAliasesShareCapacityAndUncertainSentCallsNeverExpireIntoFreeSlots() {
        var first=scheduler.acquire(user(7),request("fake"));first.sent();first.close();
        var second=scheduler.acquire(user(8),request("alias"));second.sent();second.close();
        assertEquals(2,jdbc.queryForObject("select count(*) from agent_provider_slots where state='UNKNOWN'",Integer.class));
        assertThrows(LlmException.class,()->scheduler.acquire(user(9),new LlmClient.LlmRequest("fake","fake",List.of(),List.of(),0,Duration.ofMillis(100))));
        assertEquals(2,jdbc.queryForObject("select count(*) from agent_provider_slots where state='UNKNOWN'",Integer.class));
    }
    @Test void independentInstancesSeeSameSlotsAndUnknownProviderCannotAdmit() {
        var other=new ProviderScheduler(jdbc,app.getBean(org.springframework.transaction.PlatformTransactionManager.class),app.getBean(AgentProperties.class),app.getBean(ProjectLeaseService.class));
        try(var a=scheduler.acquire(user(7),request("fake"));var b=other.acquire(user(8),request("alias"))){assertEquals(2,jdbc.queryForObject("select count(*) from agent_provider_slots where state='ACTIVE'",Integer.class));a.sent();a.completed();}
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_provider_slots where state='ACTIVE'",Integer.class));
        assertThrows(LlmAccountingException.class,()->other.acquire(user(8),request("unknown")));
    }
    @Test void simultaneousCapacityReturnDoesNotLetAnOlderBulkQueueMonopolizeTheNextTurn() throws Exception {
        var a=scheduler.acquire(user(7),request("fake"));var b=scheduler.acquire(user(8),request("alias"));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var bulk=pool.submit(()->scheduler.acquire(user(7),request("fake")));
            var small=pool.submit(()->scheduler.acquire(user(9),request("fake")));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(jdbc.queryForObject("select count(*) from agent_provider_slots where state='WAITING'",Integer.class)<2&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals(2,jdbc.queryForObject("select count(*) from agent_provider_slots where state='WAITING'",Integer.class));a.close();b.close();
            try(var bulkPermit=bulk.get(2,TimeUnit.SECONDS);var smallPermit=small.get(2,TimeUnit.SECONDS)){
                assertTrue(jdbc.queryForObject("select a.admitted_at<b.admitted_at from agent_scheduler_user_turns a,agent_scheduler_user_turns b where a.user_id=9 and b.user_id=7",Boolean.class));
            }
        }finally{a.close();b.close();}
    }
    @Configuration @Import(ProviderScheduler.class)
    static class Services {
        @Bean AgentProperties props(){var p=new AgentProperties();var cfg=p.getScheduling();cfg.setEnabled(true);cfg.setGlobalConcurrency(2);cfg.setUserConcurrency(1);cfg.setRunConcurrency(1);cfg.setMaxWaitMs(2000);for(String provider:List.of("fake","alias")){var pool=new AgentProperties.SchedulingPool();pool.setAccount("fixture-account");pool.setConcurrency(2);cfg.getPools().put(provider,pool);}return p;}
        @Bean ProjectLeaseService leases(){return org.mockito.Mockito.mock(ProjectLeaseService.class);}
    }
}
