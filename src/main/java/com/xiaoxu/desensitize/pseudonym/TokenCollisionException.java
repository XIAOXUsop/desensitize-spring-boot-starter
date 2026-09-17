package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

/**
 * 令牌碰撞：同一个令牌被要求映射到两个不同的原文。
 *
 * <p>这必须是<b>硬失败</b>而不是覆盖或忽略。原因：
 * <ul>
 *   <li>令牌是 HMAC 截断的产物，两段不同明文落到同一令牌，说明摘要位数不足或密钥被替换；</li>
 *   <li>若沿用先写入的那条映射（{@code putIfAbsent}），模型回复里的令牌会被还原成<b>另一个人的真实身份</b>——
 *       错误发生在最不该出错的地方，且没有任何迹象表明出了问题；</li>
 *   <li>若覆盖成后写入的那条，则此前所有引用该令牌的上下文全部指向错误的人，问题同样静默。</li>
 * </ul>
 *
 * <p>因此这里选择：拒绝写入、抛出异常、让调用方看见。
 *
 * <p><b>异常信息不包含任何原文</b>——只能出现令牌（不可逆的摘要）与类型，
 * 否则异常日志本身就成了个人信息泄漏点。
 */
public class TokenCollisionException extends IllegalStateException {

    private final SensitiveType type;
    private final String token;

    public TokenCollisionException(SensitiveType type, String token) {
        super(buildMessage(type, token));
        this.type = type;
        this.token = token;
    }

    public SensitiveType getType() {
        return type;
    }

    /** 发生碰撞的令牌（摘要值，不含原文） */
    public String getToken() {
        return token;
    }

    private static String buildMessage(SensitiveType type, String token) {
        return "令牌碰撞：类型 " + type + " 的令牌 " + token + " 已映射到另一段明文。"
                + "两段不同的明文得到同一令牌，意味着摘要位数不足或假名化密钥在运行中被更换；"
                + "无论保留哪一条映射，都会把某个人的数据错误还原成另一个人的，因此拒绝写入。"
                + "排查方向：确认密钥自服务启动后未被更改；若密钥未变，则说明该类型的取值空间已超出当前令牌位数，"
                + "需要升级 " + Pseudonymizer.class.getSimpleName() + " 的摘要长度。"
                + "注意：本异常不打印任何原文，请勿在排查时手动把原文写进日志。";
    }
}
