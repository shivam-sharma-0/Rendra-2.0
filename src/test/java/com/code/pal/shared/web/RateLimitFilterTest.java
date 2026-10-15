package com.code.pal.shared.web;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RateLimitFilterTest {
    @Test void untrustedForwardingCannotChangeTheBucketAndNormalizedPathsRemainLimited() throws Exception {
        var redis=mock(StringRedisTemplate.class);when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(21L);
        var props=new RateLimitProperties();var filter=new RateLimitFilter(redis,props,new ObjectMapper());
        var request=new MockHttpServletRequest("POST","/api/auth/lo%67in;fake=1");request.setRemoteAddr("203.0.113.7");request.addHeader("X-Forwarded-For","198.51.100.99");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->fail("Over-limit authentication must not run"));assertEquals(429,response.getStatus());
        verify(redis).execute(any(RedisScript.class),eq(List.of("rl:auth:203.0.113.7")),any(Object[].class));
    }
    @Test void trustedProxyWalksFromTheRightAndStorageFailureRefusesAdmission() throws Exception {
        var redis=mock(StringRedisTemplate.class);when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("Unavailable"));
        var props=new RateLimitProperties();props.setTrustedProxyIps(List.of("127.0.0.1","192.0.2.10"));var filter=new RateLimitFilter(redis,props,new ObjectMapper());
        var request=new MockHttpServletRequest("POST","/api/auth/refresh");request.setRemoteAddr("127.0.0.1");request.addHeader("X-Forwarded-For","198.51.100.99, 203.0.113.8, 192.0.2.10");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->fail("Unavailable protection must not admit"));assertEquals(503,response.getStatus());
        verify(redis).execute(any(RedisScript.class),eq(List.of("rl:auth:203.0.113.8")),any(Object[].class));
    }
}
