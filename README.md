# mcp-redis-tools

Spring Boot starter for MCP Redis tools. Provides two categories of tools:

- **KV generic**: `redis_get`, `redis_set`, `redis_del`, `redis_keys`, `redis_ttl`, `redis_incr`
- **Inter-Claude messaging** (DB 5): `claude_send`, `claude_read`, `claude_broadcast`, `claude_list_inboxes`, `claude_clear`

## Usage

```xml
<dependency>
    <groupId>io.github.massimilianopili</groupId>
    <artifactId>mcp-redis-tools</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_REDIS_ENABLED` | `false` | Enable Redis tools |
| `MCP_REDIS_URL` | `redis://redis:6379` | Redis connection URL |
| `MCP_REDIS_DB` | `0` | Database for KV tools |

Inter-Claude messaging always uses DB 5.
