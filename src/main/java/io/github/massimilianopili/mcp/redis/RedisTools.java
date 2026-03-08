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
            description = "Legge il valore di una chiave Redis. Restituisce null se la chiave non esiste.")
    public Mono<String> get(
            @ToolParam(description = "Chiave Redis") String key) {
        return kv.opsForValue().get(key)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_set",
            description = "Scrive un valore su una chiave Redis. Se ttlSeconds > 0, la chiave scade automaticamente.")
    public Mono<String> set(
            @ToolParam(description = "Chiave Redis") String key,
            @ToolParam(description = "Valore da scrivere") String value,
            @ToolParam(description = "TTL in secondi (0 = nessuna scadenza)", required = false) Integer ttlSeconds) {
        Mono<Boolean> op = (ttlSeconds != null && ttlSeconds > 0)
                ? kv.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds))
                : kv.opsForValue().set(key, value);
        return op.map(ok -> ok ? "OK" : "ERRORE")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_del",
            description = "Elimina una chiave Redis. Restituisce il numero di chiavi eliminate.")
    public Mono<String> del(
            @ToolParam(description = "Chiave Redis da eliminare") String key) {
        return kv.delete(key)
                .map(n -> "Eliminate: " + n)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_keys",
            description = "Elenca le chiavi che corrispondono a un pattern glob (es: 'user:*'). Max 100 risultati.")
    public Mono<List<String>> keys(
            @ToolParam(description = "Pattern glob, es: 'user:*' o '*'") String pattern) {
        return kv.keys(pattern)
                .take(100)
                .collectList()
                .onErrorResume(e -> Mono.just(List.of("ERRORE: " + e.getMessage())));
    }

    @ReactiveTool(name = "redis_ttl",
            description = "Restituisce il TTL rimanente di una chiave in secondi. -1 = nessuna scadenza, -2 = chiave non esistente.")
    public Mono<String> ttl(
            @ToolParam(description = "Chiave Redis") String key) {
        return kv.getExpire(key)
                .map(d -> {
                    if (d == null) return "-2";
                    long secs = d.toSeconds();
                    return secs < 0 ? String.valueOf(secs) : secs + "s";
                })
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "redis_incr",
            description = "Incrementa atomicamente il valore numerico di una chiave. Se la chiave non esiste, parte da 1.")
    public Mono<String> incr(
            @ToolParam(description = "Chiave Redis") String key) {
        return kv.opsForValue().increment(key)
                .map(String::valueOf)
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    // ─── Inter-Claude Messaging (DB 5) ─────────────────────────────────────────

    @ReactiveTool(name = "claude_send",
            description = "Invia un messaggio alla inbox di un'altra sessione Claude. " +
                    "Il destinatario legge con claude_read usando la propria label. TTL automatico 24h.")
    public Mono<String> claudeSend(
            @ToolParam(description = "Label del destinatario (es: 'session-a', 'debugger')") String to,
            @ToolParam(description = "Contenuto del messaggio") String content) {
        String key = "claude:inbox:" + to;
        return msg.opsForList().leftPush(key, content)
                .flatMap(size -> msg.expire(key, Duration.ofHours(24)).thenReturn(size))
                .map(size -> "Inviato a '" + to + "' (inbox size: " + size + ")")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "claude_read",
            description = "Legge e rimuove i messaggi dalla propria inbox. Operazione distruttiva: i messaggi letti non sono recuperabili.")
    public Mono<List<String>> claudeRead(
            @ToolParam(description = "La propria label (es: 'session-a')") String myLabel,
            @ToolParam(description = "Numero massimo di messaggi da leggere (default 10)", required = false) Integer count) {
        String key = "claude:inbox:" + myLabel;
        int n = (count != null && count > 0) ? Math.min(count, 50) : 10;
        return msg.opsForList().range(key, 0, n - 1)
                .collectList()
                .flatMap(msgs -> msg.delete(key).thenReturn(msgs))
                .onErrorResume(e -> Mono.just(List.of("ERRORE: " + e.getMessage())));
    }

    @ReactiveTool(name = "claude_broadcast",
            description = "Invia un messaggio a tutte le sessioni Claude che controllano il canale broadcast. TTL 1h.")
    public Mono<String> claudeBroadcast(
            @ToolParam(description = "Contenuto del messaggio broadcast") String content) {
        String key = "claude:inbox:__broadcast__";
        return msg.opsForList().leftPush(key, content)
                .flatMap(size -> msg.expire(key, Duration.ofHours(1)).thenReturn(size))
                .map(size -> "Broadcast inviato (coda size: " + size + ")")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }

    @ReactiveTool(name = "claude_list_inboxes",
            description = "Elenca le inbox attive (sessioni Claude con messaggi in attesa).")
    public Mono<Map<String, String>> claudeListInboxes() {
        return msg.keys("claude:inbox:*")
                .flatMap(key -> msg.opsForList().size(key)
                        .map(size -> Map.entry(key.replace("claude:inbox:", ""), size + " messaggi")))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(e -> Mono.just(Map.of("ERRORE", e.getMessage())));
    }

    @ReactiveTool(name = "claude_clear",
            description = "Svuota la propria inbox eliminando tutti i messaggi in attesa.")
    public Mono<String> claudeClear(
            @ToolParam(description = "La propria label") String myLabel) {
        return msg.delete("claude:inbox:" + myLabel)
                .map(deleted -> deleted > 0 ? "Inbox '" + myLabel + "' svuotata" : "Inbox '" + myLabel + "' era già vuota")
                .onErrorResume(e -> Mono.just("ERRORE: " + e.getMessage()));
    }
}
