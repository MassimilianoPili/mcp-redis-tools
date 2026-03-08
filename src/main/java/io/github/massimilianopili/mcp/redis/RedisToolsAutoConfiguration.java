package io.github.massimilianopili.mcp.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
@Import({RedisConfig.class, RedisTools.class})
public class RedisToolsAutoConfiguration {
    // ReactiveToolAutoConfiguration (spring-ai-reactive-tools) auto-scopre i bean
    // con metodi @ReactiveTool e crea ReactiveMethodToolCallbackProvider automaticamente.
    // Nessun ToolCallbackProvider esplicito necessario.
}
