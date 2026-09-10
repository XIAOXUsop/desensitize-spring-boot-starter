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
 * 同一原文重复登记为幂等操作。
 */
public final class InMemoryTokenVault implements TokenVault {

    private final Map<String, String> tokenToRaw = new ConcurrentHashMap<>();

    @Override
    public void remember(String token, SensitiveType type, String raw) {
        if (token == null || raw == null) {
            return;
        }
        tokenToRaw.putIfAbsent(token, raw);
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
