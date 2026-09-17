package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存令牌保险库：默认实现，适合单次请求 / 单次会话存活期。
 *
 * <p><b>注意</b>：进程重启即丢失，跨会话无法还原。生产环境应替换为加密落库的实现
 * （见 {@link TokenVault} 接口注释中的实现要点），并保证映射表不出受信边界。
 *
 * <h2>作用域</h2>
 * 映射按 {@link VaultScope} 分表存放：两个会话用同一段原文各自登记，互不可见；
 * 会话结束时 {@link #forget} 只清掉自己那一份。不指定作用域的旧接口
 * （{@link #remember(String, SensitiveType, String)} 等）落在 {@link VaultScope#GLOBAL} 上。
 *
 * <h2>过期</h2>
 * 可以用 {@link #InMemoryTokenVault(Duration)} 给条目加存活时间。过期之后
 * {@link #original} 返回空——令牌变成死串，而不是继续指向一个早就该被忘掉的身份。
 * 内存实现终究要靠进程重启兜底，TTL 只是让"忘了清"这件事有个上限。
 *
 * <h2>并发</h2>
 * 使用 {@link ConcurrentHashMap}。令牌是确定性生成的，同一作用域内重复登记同一原文为幂等。
 *
 * <h2>碰撞即失败</h2>
 * 若同一作用域内同一令牌被要求映射到不同原文，抛出 {@link TokenCollisionException}
 * 并保留原有映射。详见该异常的类型注释——这不是防御性编程，而是防止"还原成另一个人的身份"。
 */
public final class InMemoryTokenVault implements ScopedTokenVault {

    /** 作用域与令牌拼成的内部键。用不可能出现在作用域名里的分隔符，避免 `a` + `b_c` 与 `a_b` + `c` 撞键 */
    private static final char SEPARATOR = '\u0000';

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 条目存活时间；null 表示不过期 */
    private final Duration ttl;

    private final Clock clock;

    private volatile RestoreAudit audit = (scope, type) -> {
    };

    /** 不过期的保险库 */
    public InMemoryTokenVault() {
        this(null, Clock.systemUTC());
    }

    /**
     * 带存活时间的保险库。
     *
     * @param ttl 条目存活时间；必须为正数。传 null 表示不过期
     */
    public InMemoryTokenVault(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** 供测试注入时钟，让过期行为可以被确定性验证 */
    InMemoryTokenVault(Duration ttl, Clock clock) {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl 必须为正数：收到 " + ttl);
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 注册还原审计回调。
     *
     * <p>回调只拿到作用域与类型，**不含原文**——审计它自己的日志不该变成新的泄漏点。
     * 默认不注册任何回调。
     */
    public void auditWith(RestoreAudit audit) {
        this.audit = audit == null ? (scope, type) -> {
        } : audit;
    }

    // ---------- 作用域接口 ----------

    @Override
    public void remember(VaultScope scope, String token, SensitiveType type, String raw) {
        if (scope == null || token == null || raw == null) {
            return;
        }
        String key = key(scope, token);
        Entry fresh = new Entry(type, raw, expiresAt());
        Entry existing = entries.putIfAbsent(key, fresh);
        if (existing != null && !existing.expired(clock) && !existing.raw().equals(raw)) {
            // 新值未被写入，原映射保持不变，因此后续还原仍然返回第一个原文而不是静默返回第二个
            throw new TokenCollisionException(type, token);
        }
        if (existing != null && existing.expired(clock)) {
            // 过期条目不算证据：它已经不再参与还原，覆盖掉是正确行为
            entries.put(key, fresh);
        }
    }

    @Override
    public Optional<String> original(VaultScope scope, String token) {
        if (scope == null || token == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(key(scope, token));
        if (entry == null || entry.expired(clock)) {
            return Optional.empty();
        }
        audit.restored(scope, entry.type());
        return Optional.of(entry.raw());
    }

    @Override
    public int size(VaultScope scope) {
        if (scope == null) {
            return 0;
        }
        String prefix = scope.namespace() + SEPARATOR;
        int count = 0;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (entry.getKey().startsWith(prefix) && !entry.getValue().expired(clock)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void forget(VaultScope scope) {
        if (scope == null) {
            return;
        }
        String prefix = scope.namespace() + SEPARATOR;
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ---------- TokenVault 兼容接口：落在 GLOBAL 作用域 ----------

    @Override
    public void remember(String token, SensitiveType type, String raw) {
        remember(VaultScope.GLOBAL, token, type, raw);
    }

    @Override
    public Optional<String> original(String token) {
        return original(VaultScope.GLOBAL, token);
    }

    @Override
    public int size() {
        return size(VaultScope.GLOBAL);
    }

    /** 清空**全部**作用域（例如进程内测试之间重置）。清空后已发出的令牌将无法还原。 */
    public void clear() {
        entries.clear();
    }

    private Instant expiresAt() {
        return ttl == null ? null : clock.instant().plus(ttl);
    }

    private static String key(VaultScope scope, String token) {
        return scope.namespace() + SEPARATOR + token;
    }

    /** 一条映射：类型（供审计使用，不含原文）、原文、过期时刻（null 表示不过期） */
    private record Entry(SensitiveType type, String raw, Instant expiresAt) {

        boolean expired(Clock clock) {
            return expiresAt != null && !clock.instant().isBefore(expiresAt);
        }
    }
}
