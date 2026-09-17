package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.util.Optional;

/**
 * 带作用域的令牌保险库：每个会话（或租户）一份独立的映射表。
 *
 * <p>它是 {@link TokenVault} 的**扩展**而不是替换：老接口继续可用，
 * 实现类实现这个接口之后，调用方才能在会话之间做隔离与清理。
 *
 * <p>契约与 {@link TokenVault} 一致，只是所有操作都限定在给定的
 * {@link VaultScope} 内：
 * <ul>
 *   <li>不同作用域的映射表**互不可见**——各自的登记、计数与清理都不影响对方；</li>
 *   <li>同一作用域内，同令牌不同原文仍然是**硬失败**（{@link TokenCollisionException}）；</li>
 *   <li>{@link #forget} 只清掉一个作用域，不影响其他会话。</li>
 * </ul>
 */
public interface ScopedTokenVault extends TokenVault {

    /** 在指定作用域登记映射；同一作用域内同令牌不同原文抛 {@link TokenCollisionException} */
    void remember(VaultScope scope, String token, SensitiveType type, String raw);

    /** 在指定作用域取回原文；不存在或已过期时返回空 */
    Optional<String> original(VaultScope scope, String token);

    /** 指定作用域当前登记的令牌数 */
    int size(VaultScope scope);

    /**
     * 删除一个作用域的全部映射（例如会话结束）。
     *
     * <p>删除之后该会话已发出的令牌**无法再还原**——这正是删除的意义：
     * 会话结束了，就不该还能把它的令牌换成真实身份。
     */
    void forget(VaultScope scope);
}
