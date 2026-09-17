package com.xiaoxu.desensitize.pseudonym;

/**
 * 令牌保险库的作用域：一次会话（或一个租户）的命名空间。
 *
 * <p>为什么必须有它：没有作用域时整张「令牌 → 原文」表是**全局**的。三个具体后果——
 * <ul>
 *   <li>会话结束时想清掉自己那份映射，只能清掉整张表；</li>
 *   <li>两个会话的令牌空间混在一起，一个会话的碰撞会波及另一个；</li>
 *   <li>无法回答"这个令牌属于哪次会话"。</li>
 * </ul>
 *
 * <p><b>注意它不改变什么</b>：令牌本身仍然是**确定性**的——同一段原文在不同会话里
 * 依然是同一个令牌串。这是设计使然（跨轮次一致性就靠它），作用域管的是映射表的隔离，
 * 不是让令牌变成会话内唯一。若业务上需要"同一客户在不同会话里不可关联"，
 * 那要改的是密钥轮换策略，不是这里。
 *
 * <p>作用域的名字由调用方决定，通常用会话 ID、请求 ID 或租户 ID。
 * 本类不解释它的语义，只保证**不同作用域之间互不可见**。
 *
 * @param namespace 非空的作用域名
 */
public record VaultScope(String namespace) {

    /** 未指定作用域时的默认命名空间；保留给不关心隔离的调用方 */
    public static final VaultScope GLOBAL = new VaultScope("__global__");

    public VaultScope {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("作用域名不能为空：空名字会让所有会话落进同一个命名空间");
        }
    }

    public static VaultScope of(String namespace) {
        return new VaultScope(namespace);
    }

    @Override
    public String toString() {
        return namespace;
    }
}
