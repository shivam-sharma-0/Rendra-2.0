package com.code.pal.realtime.config;

import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.RealtimeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the realtime Pub/Sub channel. The container runs the
 * {@link RealtimeSubscriber} on its own threads; {@code StringRedisTemplate} and
 * {@code RedisConnectionFactory} are auto-configured by Spring Boot from {@code spring.data.redis}.
 */
@Configuration
public class RedisRealtimeConfig {

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory, RealtimeSubscriber subscriber) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RealtimeService.CHANNEL));
        return container;
    }
}
