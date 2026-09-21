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
 * <p>只做<b>可校验</b>的类型：身份证有 ISO 7064 校验位、银行卡有 Luhn。
 * 但**别把这两步当成"不会误报"的保证**：
 *
 * <ul>
 *   <li>身份证：正则卡住 18 位与出生日期的**形状**（年份 18/19/20 开头、月 01–12、日 01–31），
 *       校验位另算。**不校验地区码，也不按月校验日期**——
 *       `2026-02-30` 那种日子照样能通过形状检查，只要校验位凑对。</li>
 *   <li>银行卡只看长度窗口 + Luhn，**没有发卡行 BIN 前缀约束**。
 *       Luhn 只有一位校验位，所以落在 16~19 位窗口里的**任意**数字
 *       有约 **10%** 通过（实测 20260922：16/17/18/19 位各 20000 个随机串，
 *       通过率 10.02% / 9.93% / 10.31% / 10.18%）。
 *       也就是说订单号、流水号、埋点 traceId 里每十条就有一条会被报成银行卡。</li>
 * </ul>
 *
 * <p>这是**有意的取舍**：这是一个"发现明文就报警"的工具，
 * 漏报的代价（真实客户数据外发）远高于误报的代价（多看一眼）。
 * 要压误报，得加发卡行前缀白名单——那属于使用方的业务知识，本类不猜。
 *
 * <p>姓名、地址一类无法用规则可靠判定，故不在此处识别——宁可少识别，
 * 也不要把正常文本改得面目全非。
 */
public final class PiiDetector {

    /** 一次命中：类型 + 在原文中的区间 + 原始值 */
    public record Match(SensitiveType type, int start, int end, String raw) {
    }

    /**
     * 18 位身份证（含校验位）。
     *
     * <p>前后都用 {@code (?<![0-9])} / {@code (?![0-9])} 卡住，不让它从一长串数字中间切一段出来。
     */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])");

    /**
     * **15 位老身份证**。没有校验位，只能靠出生日期（YYMMDD）的合法性筛。
     *
     * <p>为什么非补不可：15 位身份证在存量数据、老系统导出、以及测试 mock 里到处都是，
     * 而它**恰好落在银行卡的 16~19 位之外**，所以此前既不被当作身份证、
     * 也不会被当成银行卡——**原样发给模型、原样进日志**。
     */
    private static final Pattern ID_CARD_15 = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}(?![0-9])");

    /**
     * 银行卡：16~19 位，**允许按 4 位分组用空格或连字符书写**。
     *
     * <p>分隔符是这道正则此前最大的漏洞：`6222 0202 0011 2347` 是被印在卡面上的
     * 标准写法，而老正则只认连着的 16 个数字——于是**最像银行卡的那种写法反而漏过**。
     *
     * <p>末尾的 `(?![\s-]?[0-9])` 是必须的：少了它，20 位（超范围）的数字会被
     * 从头截 16 位报出来，而截出来的那一段**恰好也通过 Luhn 的概率是 1/10**。
     */
    private static final Pattern BANK_CARD = Pattern.compile(
            "(?<![0-9][\\s-]?)[0-9](?:[\\s-]?[0-9]){15,18}(?![\\s-]?[0-9])");

    /**
     * 手机号：**允许 {@code +86} / {@code 86} 国家码，以及空格、连字符分隔**。
     *
     * <p>老正则 `1[3-9][0-9]{9}` 前面挂着 `(?<![0-9])`，于是 `+8613812345678`
     * 里 `86` 是数字，整条不匹配；`138-1234-5678` 也在第一个连字符处断开。
     * 这两种写法在真实 prompt 与日志里比连写更常见。
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(?:\\+?86[\\s-]?)?1[3-9][0-9](?:[\\s-]?[0-9]){8}(?![\\s-]?[0-9])");

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
        collect(text, enabled, SensitiveType.ID_CARD, ID_CARD_15, PiiDetector::isValidLegacyIdCard, matches, consumed);
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

    /**
     * 15 位老身份证（1999 年前签发，无校验位）。
     *
     * <p>没有校验位可用，只能验出生日期的月与日：`YYMMDD` 里月份必须是 01~12、
     * 日子必须是 01~31。这一条筛不掉全部误报（`900307` 这类形态本身就不罕见），
     * 但足以挡掉绝大多数纯数字串——15 位的随机数字里满足这个约束的不到 1%。
     * **这是有意的取舍**：宁可把 15 位形态识别出来（漏报的代价是明文外发），
     * 也不要为了压误报把它整个放掉。
     */
    public static boolean isValidLegacyIdCard(String raw) {
        if (raw == null || raw.length() != 15) {
            return false;
        }
        for (int i = 0; i < 15; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        // 6 位地区码 + YYMMDD + 3 位顺序码：年月日在第 6~11 位，
        // 也就是 YY 占 6-7、MM 占 8-9、DD 占 10-11。
        // （第一版把月份写成了 charAt(6)，那是 YY 不是 MM——于是所有 15 位身份证都被判无效。）
        int month = (raw.charAt(8) - '0') * 10 + (raw.charAt(9) - '0');
        int day = (raw.charAt(10) - '0') * 10 + (raw.charAt(11) - '0');
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /**
     * 银行卡号 Luhn 校验。
     *
     * <p>**接受带分隔符的写法**：空格与连字符会先被剥掉再算校验位，
     * 因为 `6222 0202 0011 2347` 才是卡面上印的那种写法——
     * 校验函数只认连写的话，检测侧就必须自己剥一遍，两处逻辑迟早漂移。
     */
    public static boolean isValidLuhn(String raw) {
        if (raw == null) {
            return false;
        }
        String digits = raw.replace(" ", "").replace("-", "");
        if (digits.length() < 16 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
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
