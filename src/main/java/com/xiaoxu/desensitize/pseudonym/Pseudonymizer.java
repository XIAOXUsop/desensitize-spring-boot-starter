package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 确定性假名化：把敏感值映射为**不可逆**但**稳定**的令牌。
 *
 * <p>与"掩码"的关键区别：
 * <ul>
 *   <li>掩码（{@code 110101********1234}）保留了部分原文，且同一客户每次结果相同但<b>无法跨系统关联</b>；</li>
 *   <li>令牌（{@code ID_CARD_3f9a2b7c1d}）不含任何原文，但<b>同一输入恒得同一令牌</b>——
 *       大模型在整段对话里看到的是同一个人，下游也能据此做聚合与关联，而真实身份始终不出域。</li>
 * </ul>
 *
 * <p>令牌由 <b>HMAC-SHA256(密钥, 类型|原文)</b> 截断而来，因此：
 * 没有密钥就无法由令牌反推原文，也无法伪造令牌；换密钥则全部令牌改变。
 * 需要还原时必须借助 {@link TokenVault}（令牌 → 原文），令牌本身不可逆。
 */
public final class Pseudonymizer {

    /** 令牌中哈希部分的字符数：10 位十六进制 = 40 bit，足够避免碰撞又不至于拖长文本 */
    private static final int DIGEST_LENGTH = 10;

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
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("待假名化的值不能为空");
        }
        return type.name() + "_" + digest(type, raw);
    }

    /** 令牌前缀可被外部按类型解析，故此处公开类型与令牌的构造约定 */
    public static boolean isTokenOf(SensitiveType type, String candidate) {
        return candidate != null && candidate.startsWith(type.name() + "_")
                && candidate.length() == type.name().length() + 1 + DIGEST_LENGTH;
    }

    private String digest(SensitiveType type, String raw) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // 把类型并入摘要输入：同一串数字在"身份证"与"银行卡"语境下得到不同令牌，避免语义混淆
            byte[] hash = mac.doFinal((type.name() + "|" + raw).getBytes(StandardCharsets.UTF_8));
            return toHex(hash).substring(0, DIGEST_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("假名化失败：本机不支持 " + ALGORITHM, e);
        }
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
