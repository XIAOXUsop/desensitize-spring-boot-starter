package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自由文本中的敏感数据识别（正则粗筛 + 校验位精筛）。
 *
 * <p>与 {@code @Sensitive} 注解的差别：注解解决"结构化的接口返回值"，本类解决
 * <b>非结构化文本</b>——典型场景是即将发给大模型的 prompt。那段文本里没有注解可标，
 * 只能靠识别。
 *
 * <p>只做<b>可校验</b>的类型：身份证有 ISO 7064 校验位、银行卡有 Luhn，
 * 这两步能挡掉绝大多数"看起来像"的误报。姓名、地址一类无法用规则可靠判定，
 * 故不在此处识别——宁可少识别，也不要把正常文本改得面目全非。
 */
public final class PiiDetector {

    /** 一次命中：类型 + 在原文中的区间 + 原始值 */
    public record Match(SensitiveType type, int start, int end, String raw) {
    }

    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])");

    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])[0-9]{16,19}(?![0-9])");

    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECK_CHARS = "10X98765432";

    private PiiDetector() {
    }

    /**
     * 在文本中检出所有启用的敏感项，按出现位置排序。
     *
     * <p>按 身份证 → 银行卡 → 手机号 → 邮箱 的优先级占用区间，已占用的区间不会被重复报告
     * （例如 18 位身份证不会再被当成银行卡）。
     */
    public static List<Match> detect(String text, Set<SensitiveType> enabled) {
        if (text == null || text.isEmpty() || enabled.isEmpty()) {
            return List.of();
        }

        List<Match> matches = new ArrayList<>();
        boolean[] consumed = new boolean[text.length()];

        collect(text, enabled, SensitiveType.ID_CARD, ID_CARD, PiiDetector::isValidIdCard, matches, consumed);
        collect(text, enabled, SensitiveType.BANK_CARD, BANK_CARD, PiiDetector::isValidLuhn, matches, consumed);
        collect(text, enabled, SensitiveType.PHONE, PHONE, raw -> true, matches, consumed);
        collect(text, enabled, SensitiveType.EMAIL, EMAIL, raw -> true, matches, consumed);

        matches.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return matches;
    }

    private static void collect(String text, Set<SensitiveType> enabled, SensitiveType type, Pattern pattern,
                                java.util.function.Predicate<String> validator,
                                List<Match> matches, boolean[] consumed) {
        if (!enabled.contains(type)) {
            return;
        }
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            if (!validator.test(raw)) {
                continue;
            }
            int start = matcher.start();
            int end = matcher.end();
            if (isConsumed(consumed, start, end)) {
                continue;
            }
            for (int i = start; i < end; i++) {
                consumed[i] = true;
            }
            matches.add(new Match(type, start, end, raw));
        }
    }

    private static boolean isConsumed(boolean[] consumed, int start, int end) {
        for (int i = start; i < end; i++) {
            if (consumed[i]) {
                return true;
            }
        }
        return false;
    }

    /** 身份证 18 位校验位（ISO 7064:1983, MOD 11-2） */
    public static boolean isValidIdCard(String raw) {
        if (raw == null || raw.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int digit = raw.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            sum += digit * ID_WEIGHTS[i];
        }
        return Character.toUpperCase(raw.charAt(17)) == ID_CHECK_CHARS.charAt(sum % 11);
    }

    /** 银行卡号 Luhn 校验 */
    public static boolean isValidLuhn(String raw) {
        if (raw == null || raw.length() < 16 || raw.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubled = false;
        for (int i = raw.length() - 1; i >= 0; i--) {
            int digit = raw.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
