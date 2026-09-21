package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.regex.Pattern;

/**
 * 确定性假名化：把敏感值映射为**不可逆**但**稳定**的令牌。
 *
 * <p>与"掩码"的关键区别：
 * <ul>
 *   <li>掩码（{@code 110101********1234}）保留了部分原文，且同一客户每次结果相同但<b>无法跨系统关联</b>；</li>
 *   <li>令牌（{@code ID_CARD_v2_3f9a2b7c1d5e6f...}）不含任何原文，但<b>同一输入恒得同一令牌</b>——
 *       大模型在整段对话里看到的是同一个人，下游也能据此做聚合与关联，而真实身份始终不出域。</li>
 * </ul>
 *
 * <p>令牌由 <b>HMAC-SHA256(密钥, 类型|v{版本}|原文)</b> 截断而来，因此：
 * （这里原先漏了版本段，与实现和 README 都对不上。版本段是 v1 / v2 令牌<b>不可互认</b>
 *   的依据——它必须进摘要，否则换版本时旧令牌会被当成新版本的有效令牌。）
 * 没有密钥就无法由令牌反推原文，也无法伪造令牌；换密钥则全部令牌改变。
 * 需要还原时必须借助 {@link TokenVault}（令牌 → 原文），令牌本身不可逆。
 *
 * <h2>令牌格式与版本</h2>
 * <pre>
 * TYPE_v{版本}_{十六进制摘要}
 * 例：ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618
 * </pre>
 *
 * <p><b>为什么摘要从 40 bit 提到 128 bit</b>：早期 v1 令牌只保留 10 位十六进制（40 bit），
 * 按生日界估算，约 100 万个不同明文时就有约 50% 概率出现两个明文得到同一令牌——
 * 对银行客户量级而言这不是理论风险。一旦碰撞，保险库会把两个真实身份混成一个人，
 * 属于静默的数据正确性问题。v2 使用 32 位十六进制（128 bit），
 * 同一量级下碰撞概率可忽略。
 *
 * <p>v1 令牌不再生成，但 {@link #tokenVersionOf} 仍能识别，使历史数据可以继续还原；
 * 未知的新版本号不会被误判为本实现的令牌。
 */
public final class Pseudonymizer {

    /** 当前生成的令牌版本号，格式 {@code TYPE_v2_<32 位十六进制>} */
    public static final int CURRENT_VERSION = 2;

    /** v2 摘要的十六进制位数：32 位 = 128 bit */
    public static final int DIGEST_HEX_LENGTH = 32;

    /** v1 摘要的十六进制位数：10 位 = 40 bit。<b>仅为解析历史令牌而保留，不再生成。</b> */
    public static final int LEGACY_V1_DIGEST_HEX_LENGTH = 10;

    /** v1 令牌的版本号——早期格式没有版本段，此处按事实约定为 1 */
    public static final int LEGACY_V1_VERSION = 1;

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public Pseudonymizer(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("假名化密钥不能为空：令牌必须依赖密钥，否则任何人都能伪造");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成令牌。同一 ({@code type}, {@code raw}) 恒得同一结果。
     *
     * <p>保留类型前缀是为了让大模型能理解令牌的语义（"这是一张证件号"），
     * 同时前缀本身不含任何身份信息。
     */
    public String tokenize(SensitiveType type, String raw) {
        if (type == null) {
            throw new IllegalArgumentException("敏感类型不能为空");
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("待假名化的值不能为空");
        }
        return type.name() + "_v" + CURRENT_VERSION + "_" + digest(type, raw);
    }

    /**
     * 判断候选字符串是否为本类型在本实现支持版本下的合法令牌。
     *
     * <p>只做形态校验，<b>不代表该令牌一定存在于保险库中</b>；
     * 还原时必须再由 {@link TokenVault} 确认。
     */
    public static boolean isTokenOf(SensitiveType type, String candidate) {
        return tokenVersionOf(type, candidate) != null;
    }

    /**
     * 解析令牌版本号：{@code 2} 为当前格式，{@code 1} 为历史格式。
     *
     * <p>不是本类型的令牌、摘要位数不符、含非十六进制字符、或版本号不是本实现已知的版本时，
     * 一律返回 {@code null}——宁可少还原一个令牌，也不要把别的东西当令牌替换掉。
     */
    public static Integer tokenVersionOf(SensitiveType type, String candidate) {
        if (type == null || candidate == null) {
            return null;
        }
        String prefix = type.name() + "_";
        if (!candidate.startsWith(prefix)) {
            return null;
        }
        return versionOfSuffix(candidate.substring(prefix.length()));
    }

    /** 解析类型前缀之后的版本与摘要形态 */
    private static Integer versionOfSuffix(String suffix) {
        if (suffix.startsWith("v")) {
            int separator = suffix.indexOf('_');
            if (separator < 2) {
                return null;
            }
            int version = parsePositiveInt(suffix.substring(1, separator));
            if (version != CURRENT_VERSION) {
                return null;
            }
            return isLowerHex(suffix.substring(separator + 1), DIGEST_HEX_LENGTH) ? version : null;
        }
        // v1 没有版本段，只能靠固定摘要长度识别
        return isLowerHex(suffix, LEGACY_V1_DIGEST_HEX_LENGTH) ? LEGACY_V1_VERSION : null;
    }

    /**
     * 构造匹配本类型<b>任一受支持版本</b>令牌的正则，供入站还原使用。
     *
     * <p>格式知识只保留在本类中，避免 {@link PromptRedactor} 与生成端各写一份而漂移。
     */
    public static Pattern tokenPattern(SensitiveType type) {
        String name = Pattern.quote(type.name());
        return Pattern.compile(
                "\\b" + name + "_v" + CURRENT_VERSION + "_[0-9a-f]{" + DIGEST_HEX_LENGTH + "}\\b"
                        + "|\\b" + name + "_[0-9a-f]{" + LEGACY_V1_DIGEST_HEX_LENGTH + "}\\b");
    }

    private String digest(SensitiveType type, String raw) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // 把类型与版本并入摘要输入：同一串数字在"身份证"与"银行卡"语境下得到不同令牌，
            // 且升级版本后同一原文会得到新令牌，不会与历史令牌混淆
            String payload = type.name() + "|v" + CURRENT_VERSION + "|" + raw;
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(hash).substring(0, DIGEST_HEX_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("假名化失败：本机不支持 " + ALGORITHM, e);
        }
    }

    private static boolean isLowerHex(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static int parsePositiveInt(String value) {
        if (value.isEmpty() || value.length() > 9) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
