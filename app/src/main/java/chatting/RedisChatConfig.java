package chatting;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableScheduling
public class RedisChatConfig {
    @Bean
    ChannelTopic chatEventsTopic() { return new ChannelTopic("chat:events"); }

    @Bean
    RedisMessageListenerContainer chatMessageListenerContainer(RedisConnectionFactory factory, ChatWebSocketHandler handler, ChannelTopic chatEventsTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(handler, chatEventsTopic);
        return container;
    }
}
