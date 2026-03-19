package io.github.massimilianopili.mcp.redis;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
public class RedisTools {

    private static final Logger log = LoggerFactory.getLogger(RedisTools.class);

    private final ReactiveStringRedisTemplate kv;
    private final ReactiveStringRedisTemplate msg;

    public RedisTools(
            @Qualifier("mcpRedisTemplate") ReactiveStringRedisTemplate kv,
            @Qualifier("mcpRedisMessagingTemplate") ReactiveStringRedisTemplate msg) {
        this.kv = kv;
        this.msg = msg;
    }

    // ─── KV Generico ───────────────────────────────────────────────────────────

    @ReactiveTool(name = "redis_get",
            description = "Reads the value of a Redis key. Returns null if the key does not exist.")
    public Mono<String> get(
            @ToolParam(description = "Redis key") String key) {
        return kv.opsForValue().get(key)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_set",
            description = "Sets a value on a Redis key. If ttlSeconds > 0, the key expires automatically.")
    public Mono<String> set(
            @ToolParam(description = "Redis key") String key,
            @ToolParam(description = "Value to store") String value,
            @ToolParam(description = "TTL in seconds (0 = no expiration)", required = false) Integer ttlSeconds) {
        Mono<Boolean> op = (ttlSeconds != null && ttlSeconds > 0)
                ? kv.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds))
                : kv.opsForValue().set(key, value);
        return op.map(ok -> ok ? "OK" : "ERRORE")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_del",
            description = "Deletes a Redis key. Returns the number of keys deleted.")
    public Mono<String> del(
            @ToolParam(description = "Redis key to delete") String key) {
        return kv.delete(key)
                .map(n -> "Eliminate: " + n)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_keys",
            description = "Lists keys matching a glob pattern (e.g. 'user:*'). Max 100 results.")
    public Mono<List<String>> keys(
            @ToolParam(description = "Glob pattern, e.g. 'user:*' or '*'") String pattern) {
        return kv.keys(pattern)
                .take(100)
                .collectList()
                .onErrorResume(e -> Mono.just(List.of("ERRORE: " + e.getMessage())));
    }

    @ReactiveTool(name = "redis_ttl",
            description = "Returns the remaining TTL of a key in seconds. -1 = no expiration, -2 = key does not exist.")
    public Mono<String> ttl(
            @ToolParam(description = "Redis key") String key) {
        return kv.getExpire(key)
                .map(d -> {
                    if (d == null) return "-2";
                    long secs = d.toSeconds();
                    return secs < 0 ? String.valueOf(secs) : secs + "s";
                })
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_incr",
            description = "Atomically increments the numeric value of a key. If the key does not exist, starts from 1.")
    public Mono<String> incr(
            @ToolParam(description = "Redis key") String key) {
        return kv.opsForValue().increment(key)
                .map(String::valueOf)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    // ─── Inter-Claude Messaging (DB 5) ─────────────────────────────────────────

    @ReactiveTool(name = "claude_send",
            description = "Sends a message to another Claude session's inbox. " +
                    "The recipient reads it via claude_read using their own label. Auto TTL 24h.")
    public Mono<String> claudeSend(
            @ToolParam(description = "Recipient label (e.g. 'session-a', 'debugger')") String to,
            @ToolParam(description = "Message content") String content) {
        String key = "claude:inbox:" + to;
        return msg.opsForList().leftPush(key, content)
                .flatMap(size -> msg.expire(key, Duration.ofHours(24)).thenReturn(size))
                .map(size -> "Inviato a '" + to + "' (inbox size: " + size + ")")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "claude_read",
            description = "Reads and removes messages from own inbox. Destructive operation: read messages are not recoverable.")
    public Mono<List<String>> claudeRead(
            @ToolParam(description = "Own label (e.g. 'session-a')") String myLabel,
            @ToolParam(description = "Max number of messages to read (default 10)", required = false) Integer count) {
        String key = "claude:inbox:" + myLabel;
        int n = (count != null && count > 0) ? Math.min(count, 50) : 10;
        return msg.opsForList().range(key, 0, n - 1)
                .collectList()
                .flatMap(msgs -> msg.delete(key).thenReturn(msgs))
                .onErrorResume(e -> Mono.just(List.of("ERRORE: " + e.getMessage())));
    }

    @ReactiveTool(name = "claude_broadcast",
            description = "Sends a message to all Claude sessions monitoring the broadcast channel. TTL 1h.")
    public Mono<String> claudeBroadcast(
            @ToolParam(description = "Broadcast message content") String content) {
        String key = "claude:inbox:__broadcast__";
        return msg.opsForList().leftPush(key, content)
                .flatMap(size -> msg.expire(key, Duration.ofHours(1)).thenReturn(size))
                .map(size -> "Broadcast inviato (coda size: " + size + ")")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "claude_list_inboxes",
            description = "Lists active inboxes (Claude sessions with pending messages).")
    public Mono<Map<String, String>> claudeListInboxes() {
        return msg.keys("claude:inbox:*")
                .flatMap(key -> msg.opsForList().size(key)
                        .map(size -> Map.entry(key.replace("claude:inbox:", ""), size + " messaggi")))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(e -> Mono.just(Map.of("ERRORE", e.getMessage())));
    }

    @ReactiveTool(name = "claude_clear",
            description = "Clears own inbox by deleting all pending messages.")
    public Mono<String> claudeClear(
            @ToolParam(description = "Own label") String myLabel) {
        return msg.delete("claude:inbox:" + myLabel)
                .map(deleted -> deleted > 0 ? "Inbox '" + myLabel + "' svuotata" : "Inbox '" + myLabel + "' era già vuota")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    // ─── Session Registry (Redis HASH claude:registry, DB 5) ──────────────────

    @ReactiveTool(name = "claude_who",
            description = "Lists active Claude sessions registered in the registry. " +
                    "Each entry contains chatId, sessionId, project, role, startedAt.")
    public Mono<Map<String, String>> claudeWho() {
        return msg.opsForHash().entries("claude:registry")
                .collectMap(e -> e.getKey().toString(), e -> e.getValue().toString())
                .onErrorResume(e -> Mono.just(Map.of("ERRORE", e.getMessage())));
    }

    @ReactiveTool(name = "claude_register",
            description = "Registers or updates the current session in the registry with role and capabilities. " +
                    "The registry is a Redis HASH on DB 5 (claude:registry).")
    public Mono<String> claudeRegister(
            @ToolParam(description = "Session label (e.g. 'chat-31' or 'sub-31-research')") String label,
            @ToolParam(description = "JSON with {chatId, sessionId, project, role, startedAt, capabilities}") String registrationJson) {
        return msg.opsForHash().put("claude:registry", label, registrationJson)
                .map(ok -> "Registrato: " + label)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }
}
