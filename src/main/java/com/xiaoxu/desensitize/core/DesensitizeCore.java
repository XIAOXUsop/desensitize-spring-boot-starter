package com.xiaoxu.desensitize.core;

import com.xiaoxu.desensitize.annotation.SensitiveType;

/**
 * 各类型掩码核心算法（无状态，纯函数）。
 *
 * <p>占位字符默认 {@code '*'}，可通过 {@code xiaoxu.desensitize.mask-char} 全局替换。
 */
public final class DesensitizeCore {

    /** 默认占位字符 */
    public static final char DEFAULT_MASK_CHAR = '*';

    private DesensitizeCore() {
    }

    public static String mask(SensitiveType type, String raw, int keepFirst, int keepLast) {
        return mask(type, raw, keepFirst, keepLast, DEFAULT_MASK_CHAR);
    }

    public static String mask(SensitiveType type, String raw, int keepFirst, int keepLast, char maskChar) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return switch (type) {
            case CUSTOM -> maskKeep(raw, keepFirst, keepLast, maskChar);
            case ID_CARD -> maskKeep(raw, 6, 4, maskChar);
            case PHONE -> maskKeep(raw, 3, 4, maskChar);
            case NAME -> maskKeep(raw, 1, 0, maskChar);
            case EMAIL -> maskEmail(raw, maskChar);
            case BANK_CARD -> maskKeep(raw, 0, 4, maskChar);
            case ADDRESS -> maskKeep(raw, 6, 0, maskChar);
            case IP -> maskIp(raw, maskChar);
        };
    }

    /** 通用掩码：保留前 keepFirst 位 + 后 keepLast 位，中间填占位字符 */
    static String maskKeep(String raw, int keepFirst, int keepLast, char maskChar) {
        int len = raw.length();
        if (keepFirst + keepLast >= len) {
            // 长度不够保留时整体打码但保留首字符
            return raw.charAt(0) + repeat(maskChar, 3);
        }
        return raw.substring(0, keepFirst)
                + repeat(maskChar, 3)
                + raw.substring(len - keepLast);
    }

    /** 邮箱：保留首字符与 @ 之后内容 */
    static String maskEmail(String raw, char maskChar) {
        int at = raw.indexOf('@');
        if (at <= 0) {
            return maskKeep(raw, 1, 0, maskChar);
        }
        return raw.charAt(0) + repeat(maskChar, 3) + raw.substring(at);
    }

    /** IPv4：保留前两段 */
    static String maskIp(String raw, char maskChar) {
        String[] parts = raw.split("\\.");
        if (parts.length != 4) {
            return maskKeep(raw, 5, 0, maskChar);
        }
        return parts[0] + "." + parts[1] + "." + maskChar + "." + maskChar;
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }
}
