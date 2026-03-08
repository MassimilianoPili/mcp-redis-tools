package io.github.massimilianopili.mcp.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    // DB 5 isolato: Gitea usa 0/1/2, Preference Sort usa 4
    static final int MESSAGING_DB = 5;

    @Value("${mcp.redis.url:redis://redis:6379}")
    private String redisUrl;

    @Value("${mcp.redis.db:0}")
    private int kvDb;

    @Bean(name = "mcpKvConnectionFactory")
    @Primary
    public LettuceConnectionFactory mcpKvConnectionFactory() {
        return buildFactory(kvDb);
    }

    @Bean(name = "mcpMessagingConnectionFactory")
    public LettuceConnectionFactory mcpMessagingConnectionFactory() {
        return buildFactory(MESSAGING_DB);
    }

    @Bean(name = "mcpRedisTemplate")
    @Primary
    public ReactiveStringRedisTemplate mcpRedisTemplate(
            @Qualifier("mcpKvConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    @Bean(name = "mcpRedisMessagingTemplate")
    public ReactiveStringRedisTemplate mcpRedisMessagingTemplate(
            @Qualifier("mcpMessagingConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    private LettuceConnectionFactory buildFactory(int db) {
        try {
            URI uri = URI.create(redisUrl);
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            config.setDatabase(db);
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                if (parts.length == 2) config.setPassword(parts[1]);
            }
            log.info("Redis MCP: {}:{} db={}", uri.getHost(), uri.getPort(), db);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("Redis MCP config non valida (MCP_REDIS_URL=" + redisUrl + "): " + e.getMessage(), e);
        }
    }
}
