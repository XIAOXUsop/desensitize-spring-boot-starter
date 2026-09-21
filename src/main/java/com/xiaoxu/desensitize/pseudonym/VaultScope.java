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
        /*
         * 控制字符一律拒绝。**这不是洁癖，是隔离能不能成立的前提。**
         *
         * InMemoryTokenVault 用「作用域名 + 控制字符分隔符 + 令牌」拼内部键，而 size/forget
         * 是按「作用域名 + 分隔符」做**前缀匹配**的。它原来的注释写着「用不可能出现在
         * 作用域名里的分隔符」——**那句话从来没被强制过**：本类只拦 null 与 blank，
         * 于是 `"session-a\u0000evil"` 是合法的，而它会落进 `"session-a"` 的前缀里。
         *
         * 实测（2026-09-22）的三种症状：size("session-a") 把别人的登记算进去；
         * forget("session-a") 连带删掉另一个作用域的映射，那一边从此读不回原文。
         * 影响是跨会话的映射销毁与计数错乱（读不到别人的原文——original() 是精确查键）。
         *
         * 拒绝控制字符而不是只拒绝那一个分隔符：作用域名是会话 / 请求 / 租户 ID，
         * 里面出现控制字符本身就是异常输入。而「分隔符本身也是控制字符」这件事由测试绑住
         * ——ScopedTokenVaultTest 直接拿 InMemoryTokenVault.SEPARATOR 去构造名字并断言被拒，
         * 哪天有人把分隔符换成可打印字符，那条测试会先红。
         */
        for (int i = 0; i < namespace.length(); i++) {
            if (Character.isISOControl(namespace.charAt(i))) {
                throw new IllegalArgumentException(
                        "作用域名不能含控制字符（第 " + i + " 位是 U+"
                                + String.format("%04X", (int) namespace.charAt(i))
                                + "）：保险库用控制字符分隔作用域与令牌，含控制字符的名字会与别的会话撞键");
            }
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
