package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

/**
 * 还原审计回调：记录"谁在什么时候把哪一类令牌换回了真实值"。
 *
 * <p><b>刻意不传原文。</b>回调拿到的只有作用域与类型，没有值——审计日志本身
 * 不该成为新的个人信息泄漏点。要知道还原了哪一条，看作用域与类型就够了；
 * 想看具体值，那说明你想要的不是审计而是数据，应该走别的、有权限控制的通道。
 *
 * <p>默认实现不注册任何回调：日志是要成本的，而且"默认记点什么"很容易
 * 变成没人看的一堆噪音。要不要审计由调用方显式决定。
 */
@FunctionalInterface
public interface RestoreAudit {

    /**
     * 一次成功还原。
     *
     * @param scope 发生还原的作用域
     * @param type  被还原的敏感类型——<b>不含原文</b>
     */
    void restored(VaultScope scope, SensitiveType type);
}
