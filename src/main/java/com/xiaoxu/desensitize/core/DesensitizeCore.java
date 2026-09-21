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

        // keepFirst / keepLast 是使用者写在注解里的配置，写错不该把接口打成 500。
        // 实测（2026-09-22）：`@Sensitive(type = CUSTOM, keepFirst = -1, keepLast = -1)`
        // 会走到下面的 `raw.substring(0, -1)` 抛 StringIndexOutOfBoundsException；
        // 而 README 承诺的是"长度不足时不会越界"——当时只覆盖了"不够长"，
        // 没覆盖"给了负数"。负数钳到 0（与"不保留"同义）。
        //
        // 上限也要钳：keepFirst 给 Integer.MAX_VALUE 时 `keepFirst + keepLast` 会溢出成负数，
        // 于是绕过下面那个 `>= len` 判断，接着在 substring 上抛。
        if (keepFirst < 0) {
            keepFirst = 0;
        }
        if (keepLast < 0) {
            keepLast = 0;
        }
        if (keepFirst > len) {
            keepFirst = len;
        }
        if (keepLast > len) {
            keepLast = len;
        }

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
