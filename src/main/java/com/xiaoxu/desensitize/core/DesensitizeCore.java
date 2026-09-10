package com.xiaoxu.desensitize.core;

import com.xiaoxu.desensitize.annotation.SensitiveType;

/**
 * 各类型掩码核心算法（无状态，纯函数）
 */
public final class DesensitizeCore {

    private DesensitizeCore() {
    }

    public static String mask(SensitiveType type, String raw, int keepFirst, int keepLast) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return switch (type) {
            case CUSTOM -> maskKeep(raw, keepFirst, keepLast);
            case ID_CARD -> maskKeep(raw, 6, 4);
            case PHONE -> maskKeep(raw, 3, 4);
            case NAME -> maskKeep(raw, 1, 0);
            case EMAIL -> maskEmail(raw);
            case BANK_CARD -> maskKeep(raw, 0, 4);
            case ADDRESS -> maskKeep(raw, 6, 0);
            case IP -> maskIp(raw);
        };
    }

    /** 通用掩码：保留前 keepFirst 位 + 后 keepLast 位，中间填 *** */
    static String maskKeep(String raw, int keepFirst, int keepLast) {
        int len = raw.length();
        if (keepFirst + keepLast >= len) {
            // 长度不够保留时整体打码但保留首字符
            return raw.charAt(0) + "***";
        }
        return raw.substring(0, keepFirst) + "***" + raw.substring(len - keepLast);
    }

    /** 邮箱：保留首字符与 @ 之后内容 */
    static String maskEmail(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0) {
            return maskKeep(raw, 1, 0);
        }
        return raw.charAt(0) + "***" + raw.substring(at);
    }

    /** IPv4：保留前两段 */
    static String maskIp(String raw) {
        String[] parts = raw.split("\\.");
        if (parts.length != 4) {
            return maskKeep(raw, 5, 0);
        }
        return parts[0] + "." + parts[1] + ".*.*";
    }
}
