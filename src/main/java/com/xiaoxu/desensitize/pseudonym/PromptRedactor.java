package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型输入输出脱敏：<b>发送前把敏感数据换成令牌，收到回复后把令牌还原回真实值</b>。
 *
 * <p>解决的问题很具体——银行不敢把真实客户数据发给外部大模型，但业务又需要模型基于
 * 真实上下文做判断。掩码解决不了：一则掩码保留了部分原文（仍是个人信息），
 * 二则模型无法凭掩码在整段对话里认出"是同一个人"。
 *
 * <pre>
 * 用户：帮我看下客户 110101199003078531 的交易是否可疑
 *   ↓ redact()
 * 发给模型：帮我看下客户 ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618 的交易是否可疑
 *   ↓ 模型回复
 * 模型回复：客户 ID_CARD_v2_9f2c4a... 近 3 月有 14 笔等额存取，建议转人工
 *   ↓ restore()
 * 展示给柜员：客户 110101199003078531 近 3 月有 14 笔等额存取，建议转人工
 * </pre>
 *
 * <p>确定性令牌保证了多轮对话的一致性：同一客户在整段会话里始终是同一个令牌，
 * 模型能正确地把上下文关联到同一个人。
 *
 * <p><b>边界说明</b>：脱敏只保护"传输与推理"环节。真实值最终仍会回到应用侧，
 * 因此应用侧本身的权限与审计不能被这一层替代。
 */
public final class PromptRedactor {

    /** 默认识别并可还原的类型：只包含能用规则可靠判定的那些 */
    public static final Set<SensitiveType> DEFAULT_TYPES =
            EnumSet.of(SensitiveType.ID_CARD, SensitiveType.BANK_CARD, SensitiveType.PHONE, SensitiveType.EMAIL);

    private final Pseudonymizer pseudonymizer;
    private final TokenVault vault;
    private final Set<SensitiveType> types;

    /** 绑定到某个会话作用域时才有值；null 表示用保险库的默认（全局）作用域 */
    private final VaultScope scope;

    public PromptRedactor(Pseudonymizer pseudonymizer, TokenVault vault) {
        this(pseudonymizer, vault, DEFAULT_TYPES);
    }

    public PromptRedactor(Pseudonymizer pseudonymizer, TokenVault vault, Set<SensitiveType> types) {
        this(pseudonymizer, vault, null, types);
    }

    private PromptRedactor(Pseudonymizer pseudonymizer, TokenVault vault, VaultScope scope, Set<SensitiveType> types) {
        this.pseudonymizer = pseudonymizer;
        this.vault = vault;
        this.scope = scope;
        this.types = Set.copyOf(types);
    }

    /**
     * 绑定到一个会话作用域的脱敏器。
     *
     * <p>为什么需要它：不绑作用域时，整张「令牌 → 原文」表是全局的。
     * 两个互不相关的会话只要用到同一段原文就会共享映射——令牌因此变成跨会话可关联的标识，
     * 而会话结束时也没法只清掉自己那一份。
     *
     * <p>绑上之后：同一段原文在不同会话里各自登记、互不可见；
     * 会话结束时对保险库调 {@link ScopedTokenVault#forget(VaultScope)} 即可整体撤销。
     *
     * @param scopedVault 支持作用域的保险库
     * @param scope       本次会话的作用域
     */
    public static PromptRedactor scoped(Pseudonymizer pseudonymizer, ScopedTokenVault scopedVault,
            VaultScope scope, Set<SensitiveType> types) {
        if (scopedVault == null) {
            throw new IllegalArgumentException("scopedVault 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空：不指定作用域就不是会话级的脱敏器");
        }
        return new PromptRedactor(pseudonymizer, scopedVault, scope, types);
    }

    /** 本次脱敏使用的会话作用域；未绑定时为空 */
    public java.util.Optional<VaultScope> scope() {
        return java.util.Optional.ofNullable(scope);
    }

    /**
     * 出站脱敏：把文本中的敏感数据替换为确定性令牌，并在保险库登记映射。
     * 没有命中时原样返回（不产生任何改写）。
     *
     * @throws TokenCollisionException 保险库中同一令牌已对应另一段原文（见 {@link TokenVault}）
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<PiiDetector.Match> matches = PiiDetector.detect(text, types);
        if (matches.isEmpty()) {
            return text;
        }

        StringBuilder redacted = new StringBuilder(text.length() + 16);
        int cursor = 0;
        for (PiiDetector.Match match : matches) {
            redacted.append(text, cursor, match.start());
            String token = pseudonymizer.tokenize(match.type(), match.raw());
            remember(token, match.type(), match.raw());
            redacted.append(token);
            cursor = match.end();
        }
        redacted.append(text, cursor, text.length());
        return redacted.toString();
    }

    /**
     * 入站还原：把模型回复中的令牌换回真实值。
     *
     * <p>两道关卡，缺一不可：
     * <ol>
     *   <li>形态合法——匹配 {@link Pseudonymizer#tokenPattern} 定义的令牌格式（含历史 v1 格式）；
     *   <li>保险库中确实存在——不存在就<b>保持原样</b>。模型完全可能自己编一个"看起来像令牌"的字符串，
     *       猜测性替换等于把编造的内容当成真实身份展示出去。
     * </ol>
     */
    public String restore(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String restored = text;
        for (SensitiveType type : types) {
            restored = restoreType(restored, type);
        }
        return restored;
    }

    private String restoreType(String text, SensitiveType type) {
        Pattern tokenPattern = tokenPattern(type);
        Matcher matcher = tokenPattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        StringBuilder restored = new StringBuilder(text.length());
        int cursor = 0;
        do {
            restored.append(text, cursor, matcher.start());
            String token = matcher.group();
            restored.append(original(token).orElse(token));
            cursor = matcher.end();
        } while (matcher.find());
        restored.append(text, cursor, text.length());
        return restored.toString();
    }

    /**
     * 给日志/异常信息消毒：把敏感值换成固定占位符，**不登记映射、不生成可达令牌**。
     *
     * <p>为什么不能直接用 {@link #redact}：那会把这段文本里的值登记进保险库，
     * 于是"只在日志里出现过一次"的东西变成了一枚可还原的令牌——
     * 日志消毒不该有这种副作用。反过来，日志里出现原文同样是泄漏，
     * 所以也不能不管。
     *
     * <p>占位符是 {@code [REDACTED:类型]}，会保留"这里原本是什么类型"这个信息，
     * 方便定位问题，但不保留任何可还原的线索。
     */
    public String sanitizeForLog(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<PiiDetector.Match> matches = PiiDetector.detect(text, types);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder sanitized = new StringBuilder(text.length() + 16);
        int cursor = 0;
        for (PiiDetector.Match match : matches) {
            sanitized.append(text, cursor, match.start());
            sanitized.append("[REDACTED:").append(match.type().name()).append(']');
            cursor = match.end();
        }
        sanitized.append(text, cursor, text.length());
        return sanitized.toString();
    }

    private void remember(String token, SensitiveType type, String raw) {
        if (scope != null && vault instanceof ScopedTokenVault scopedVault) {
            scopedVault.remember(scope, token, type, raw);
            return;
        }
        vault.remember(token, type, raw);
    }

    private java.util.Optional<String> original(String token) {
        if (scope != null && vault instanceof ScopedTokenVault scopedVault) {
            return scopedVault.original(scope, token);
        }
        return vault.original(token);
    }

    private static Pattern tokenPattern(SensitiveType type) {
        // 令牌格式集中在 Pseudonymizer 中定义，此处只负责取用，避免生成端与解析端各写一份而漂移
        return Pseudonymizer.tokenPattern(type);
    }
}
