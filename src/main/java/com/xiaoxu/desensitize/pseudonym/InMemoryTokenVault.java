package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存令牌保险库：默认实现，适合单次请求 / 单次会话存活期。
 *
 * <p><b>注意</b>：进程重启即丢失，跨会话无法还原。生产环境应替换为加密落库的实现
 * （见 {@link TokenVault} 接口注释中的实现要点），并保证映射表不出受信边界。
 *
 * <p>并发安全：使用 {@link ConcurrentHashMap}，令牌是确定性生成的，
 * 同一令牌重复登记同一原文为幂等操作。
 *
 * <p><b>碰撞即失败</b>：若同一令牌被要求映射到不同原文，抛出 {@link TokenCollisionException}
 * 并保留原有映射。详见该异常的类型注释——这不是防御性编程，而是防止"还原成另一个人的身份"。
 */
public final class InMemoryTokenVault implements TokenVault {

    private final Map<String, String> tokenToRaw = new ConcurrentHashMap<>();

    @Override
    public void remember(String token, SensitiveType type, String raw) {
        if (token == null || raw == null) {
            return;
        }
        String existing = tokenToRaw.putIfAbsent(token, raw);
        if (existing != null && !existing.equals(raw)) {
            // 新值未被写入，原映射保持不变，因此后续还原仍然返回第一个原文而不是静默返回第二个
            throw new TokenCollisionException(type, token);
        }
    }

    @Override
    public Optional<String> original(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokenToRaw.get(token));
    }

    @Override
    public int size() {
        return tokenToRaw.size();
    }

    /** 清空映射（例如会话结束）。清空后已发出的令牌将无法还原。 */
    public void clear() {
        tokenToRaw.clear();
    }
}
