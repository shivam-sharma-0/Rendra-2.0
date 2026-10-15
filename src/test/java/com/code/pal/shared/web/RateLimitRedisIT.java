package com.code.pal.testsupport;

import com.code.pal.shared.web.RateLimitFilter;
import com.code.pal.shared.web.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
class RateLimitRedisIT {
    @Test void concurrentRequestsCannotOverspendAndOrphanCountersReceiveExpiry() throws Exception {
        try(var context=CodexIntegrationEnvironment.load(System.getProperty("codex.it.project"),Integer.parseInt(System.getProperty("codex.it.postgres.port")),Integer.parseInt(System.getProperty("codex.it.redis.port")))) {
            var env=context.getEnvironment();var factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),Integer.parseInt(env.getRequiredProperty("spring.data.redis.port"))));
            factory.afterPropertiesSet();var redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
            String ip="198.18.0.71",key="rl:auth:"+ip;
            var props=new RateLimitProperties();props.setAuthRequests(5);props.setAuthWindowSeconds(30);var filter=new RateLimitFilter(redis,props,new ObjectMapper());
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                redis.delete(key);var start=new CountDownLatch(1);var admitted=new AtomicInteger();var limited=new AtomicInteger();var calls=new java.util.ArrayList<Future<?>>();
                for(int i=0;i<24;i++)calls.add(pool.submit(()->{start.await();var req=new MockHttpServletRequest("POST","/api/auth/login");req.setRemoteAddr(ip);var response=new MockHttpServletResponse();filter.doFilter(req,response,(r,s)->admitted.incrementAndGet());if(response.getStatus()==429)limited.incrementAndGet();else assertEquals(200,response.getStatus());return null;}));
                start.countDown();for(var call:calls)call.get(15,TimeUnit.SECONDS);
                assertEquals(5,admitted.get());assertEquals(19,limited.get());assertTrue(redis.getExpire(key)>0);assertEquals("24",redis.opsForValue().get(key));
                redis.opsForValue().set(key,"24");assertEquals(-1L,redis.getExpire(key));var req=new MockHttpServletRequest("POST","/api/auth/refresh");req.setRemoteAddr(ip);var response=new MockHttpServletResponse();filter.doFilter(req,response,(r,s)->fail("Orphan counter must still reject"));assertEquals(429,response.getStatus());assertTrue(redis.getExpire(key)>0);
            } finally {try{redis.delete(key);}finally{factory.destroy();}}
        }
    }
}
