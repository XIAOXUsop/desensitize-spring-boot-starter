package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.util.Optional;

/**
 * 令牌保险库：保存「令牌 → 原文」的映射，使假名化<b>可逆</b>。
 *
 * <p>为什么需要它：HMAC 令牌本身不可逆（这是安全属性，不是缺陷）。
 * 但业务上又必须能把大模型回复里的令牌还原成真实值——
 * 例如模型说"客户 ID_CARD_3f9a2b7c1d 命中制裁名单"，展示给柜员时必须还原成真实身份。
 * 于是把"不可逆的令牌"与"受控的映射表"分开：<b>令牌可以到处传，映射表只留在受信边界内</b>。
 *
 * <p>生产环境的实现要点（本接口刻意保持最小）：
 * <ul>
 *   <li>映射表应加密存储，且与令牌密钥分开管理；</li>
 *   <li>每次 {@link #original} 还原都应落审计日志（谁、何时、还原了哪类数据）；</li>
 *   <li>应支持按时间/客户维度的删除（被遗忘权）；</li>
 *   <li>{@link #remember} 遇到令牌碰撞必须失败而不是覆盖，见下。</li>
 * </ul>
 *
 * <p><b>实现约定：同令牌不同原文必须失败。</b>{@link #remember} 允许幂等重复写入，
 * 但若同一令牌对应的原文与已登记的<b>不同</b>，必须抛出 {@link TokenCollisionException}
 * 并保留原映射——静默保留或静默覆盖都会导致还原出错误的人，且没有任何外部症状。
 */
public interface TokenVault {

    /**
     * 记录令牌与原值的映射；同一令牌重复记录<b>相同</b>原文应当幂等。
     *
     * @throws TokenCollisionException 令牌已存在且对应另一段原文
     */
    void remember(String token, SensitiveType type, String raw);

    /** 取回原文；令牌不存在时返回空 */
    Optional<String> original(String token);

    /** 当前已登记的令牌数量（用于容量观测） */
    int size();
}
