package org.dromara.ticket.config;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class TicketPythonRedisConfig {

    private final TicketPythonExecutorProperties properties;

    @Bean(name = "ticketPythonStringRedisTemplate")
    public StringRedisTemplate ticketPythonStringRedisTemplate() throws Exception {
        RedissonConnectionFactory connectionFactory = createPythonRedisConnectionFactory();
        connectionFactory.afterPropertiesSet();
        return new StringRedisTemplate(connectionFactory);
    }

    private RedissonConnectionFactory createPythonRedisConnectionFactory() {
        TicketPythonExecutorProperties.Redis redis = properties.getRedis();
        Config config = new Config();
        String protocol = redis.isSsl() ? "rediss://" : "redis://";
        var singleServer = config.useSingleServer()
            .setAddress(protocol + redis.getHost() + ":" + redis.getPort())
            .setDatabase(redis.getDatabase())
            .setTimeout((int) redis.getTimeout().toMillis())
            .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize())
            .setConnectionPoolSize(redis.getConnectionPoolSize())
            .setSubscriptionConnectionPoolSize(redis.getSubscriptionConnectionPoolSize())
            .setRetryAttempts(redis.getRetryAttempts())
            .setRetryInterval(redis.getRetryIntervalMs());
        if (StringUtils.isNotBlank(redis.getPassword())) {
            singleServer.setPassword(redis.getPassword());
        }
        return new RedissonConnectionFactory(config);
    }
}
