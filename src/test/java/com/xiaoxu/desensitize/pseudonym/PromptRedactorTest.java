package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型输入输出脱敏测试：确定性、去原文、可还原、多轮一致。
 * 全离线，不调用任何模型。
 */
class PromptRedactorTest {

    private static final String ID_CARD = "110101199003078531";
    private static final String BANK_CARD = "4539578763621486";
    private static final String PHONE = "13812345678";

    private final InMemoryTokenVault vault = new InMemoryTokenVault();
    private final PromptRedactor redactor =
            new PromptRedactor(new Pseudonymizer("unit-test-secret"), vault);

    // ---------- 令牌本身 ----------

    @Test
    void tokenIsDeterministicForSameInput() {
        Pseudonymizer pseudonymizer = new Pseudonymizer("secret-a");

        assertEquals(pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD),
                pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD));
    }

    @Test
    void tokenDiffersAcrossSecretsSoItCannotBeForged() {
        String withSecretA = new Pseudonymizer("secret-a").tokenize(SensitiveType.ID_CARD, ID_CARD);
        String withSecretB = new Pseudonymizer("secret-b").tokenize(SensitiveType.ID_CARD, ID_CARD);

        assertNotEquals(withSecretA, withSecretB);
    }

    @Test
    void sameDigitsUnderDifferentTypesGetDifferentTokens() {
        Pseudonymizer pseudonymizer = new Pseudonymizer("secret");

        // 同一串数字在"身份证"与"银行卡"语境下语义不同，不应产生相同令牌
        assertNotEquals(pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD),
                pseudonymizer.tokenize(SensitiveType.BANK_CARD, ID_CARD));
    }

    @Test
    void tokenContainsNoOriginalValue() {
        String token = new Pseudonymizer("secret").tokenize(SensitiveType.ID_CARD, ID_CARD);

        assertFalse(token.contains(ID_CARD));
        assertTrue(token.startsWith("ID_CARD_v2_"));
    }

    @Test
    void blankSecretIsRejected() {
        // 无密钥的令牌任何人都能算出来，等同没做假名化
        assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer(" "));
        assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer(null));
    }

    // ---------- 出站脱敏 ----------

    @Test
    void redactRemovesEveryPlaintextOccurrence() {
        String prompt = "客户 " + ID_CARD + " 的手机号 " + PHONE + "，卡号 " + BANK_CARD;

        String redacted = redactor.redact(prompt);

        assertFalse(redacted.contains(ID_CARD), redacted);
        assertFalse(redacted.contains(PHONE), redacted);
        assertFalse(redacted.contains(BANK_CARD), redacted);
        assertTrue(redacted.contains("ID_CARD_"), redacted);
        assertTrue(redacted.contains("PHONE_"), redacted);
        assertTrue(redacted.contains("BANK_CARD_"), redacted);
    }

    /**
     * **同一段数字同时通过两道校验时，只能报一次。**
     *
     * <p>`PiiDetector` 按 身份证 → 银行卡 → 手机号 → 邮箱 的优先级占用区间，
     * 已占用的区间不再重复报告（`isConsumed`）——这段逻辑此前**没有任何测试**：
     * 把那次 `continue` 删掉，98 条用例**全绿**。
     *
     * <p>而它是活的，代价还很大。`110101199003070054` 是一个**校验位合法、
     * 同时又通过 Luhn** 的 18 位身份证（两个条件都验过），此时两轮都会命中同一区间：
     *
     * <pre>
     *   有 isConsumed: detect() -> [ID_CARD]
     *   没有        : detect() -> [ID_CARD, BANK_CARD]（同一区间报两次）
     *                 redact()  -> IndexOutOfBoundsException: Range [21, 3) out of bounds for length 26
     * </pre>
     *
     * <p>也就是说，脱敏流程不是"多报一条"，是**直接崩在调用方那里**。
     */
    @Test
    void aValueValidAsTwoKindsIsReportedExactlyOnce() {
        String dual = "110101199003070054";
        assertTrue(PiiDetector.isValidIdCard(dual), "前提：它得是一个校验位合法的身份证");
        assertTrue(PiiDetector.isValidLuhn(dual), "前提：它同时得通过 Luhn");

        var matches = PiiDetector.detect(dual, PromptRedactor.DEFAULT_TYPES);

        assertEquals(1, matches.size(), "同一区间只应报一次，实际：" + matches);
        assertEquals(SensitiveType.ID_CARD, matches.get(0).type(), "优先级高的类型应当胜出");

        // 真正要守的是这条：脱敏流程不能因此抛异常
        String redacted = redactor.redact("客户 " + dual + " 需要复核");
        assertFalse(redacted.contains(dual), redacted);
        assertEquals("客户 " + dual + " 需要复核", redactor.restore(redacted));
    }

    /**
     * **两条已知的误报——钉住它们，是为了让"边界变了"这件事有人看得见。**
     *
     * <p>检出用的是"形状 + 校验位"，不是真正的业务校验：
     *
     * <ol>
     *   <li>{@code 110101202602301234}：出生日期是 **2 月 30 日**。
     *       正则只卡"月 01–12、日 01–31"的形状，不按月校验，校验位又凑对了，于是照报；</li>
     *   <li>{@code 2026092200000001}：一串 16 位订单号，**恰好通过 Luhn**。
     *       Luhn 只有一位校验位，落在 16~19 位窗口里的任意数字约 **10%** 会通过
     *       （实测 16/17/18/19 位各 20000 个随机串：10.02% / 9.93% / 10.31% / 10.18%）。</li>
     * </ol>
     *
     * <p>这是有意的取舍——漏报的代价远高于误报。所以这里断言的不是"它们**应该**被报"，
     * 而是**"今天的边界在这里"**：哪天加了发卡行前缀约束、或按月校验日期，
     * 这两条会红——那说明边界收紧了，是好事，把它改掉并在 README 里写清新边界即可。
     * 反过来，如果哪天它**静默地**不再报，也会在这里被拦下。
     */
    @Test
    void knownFalsePositivesArePinnedAsTodayBoundary() {
        assertEquals(SensitiveType.ID_CARD,
                PiiDetector.detect("110101202602301234", PromptRedactor.DEFAULT_TYPES).get(0).type(),
                "2 月 30 日这种日期目前只过形状检查，不按月校验");

        assertEquals(SensitiveType.BANK_CARD,
                PiiDetector.detect("2026092200000001", PromptRedactor.DEFAULT_TYPES).get(0).type(),
                "通过 Luhn 的 16 位订单号目前会被当成银行卡");
    }

    @Test
    void textWithoutSensitiveDataIsReturnedUnchanged() {
        String prompt = "本月交易笔数较上月上升 12%，是否需要关注？";
        assertEquals(prompt, redactor.redact(prompt));
    }

    // ---------- 入站还原 ----------

    @Test
    void redactThenRestoreRoundTripsExactly() {
        String prompt = "客户 " + ID_CARD + " 的手机号 " + PHONE;

        String restored = redactor.restore(redactor.redact(prompt));

        assertEquals(prompt, restored);
    }

    @Test
    void sameCustomerKeepsSameTokenAcrossTurns() {
        String firstTurn = redactor.redact("客户 " + ID_CARD + " 本月交易异常");
        String secondTurn = redactor.redact("这个客户 " + ID_CARD + " 上月是否正常");

        String firstToken = tokenIn(firstTurn, "ID_CARD_");
        String secondToken = tokenIn(secondTurn, "ID_CARD_");

        // 多轮对话里模型必须能认出"是同一个人"，否则上下文关联会断
        assertEquals(firstToken, secondToken);
    }

    @Test
    void unknownTokenIsLeftUntouchedRatherThanGuessed() {
        String modelReply = "客户 ID_CARD_0000000000 疑似命中制裁名单";

        // 保险库里没有的令牌保持原样：宁可留下令牌，也不要猜测性替换
        assertEquals(modelReply, redactor.restore(modelReply));
    }

    @Test
    void malformedTokenIsNotRestoredEvenIfItLooksLikeOne() {
        // 模型很容易编出"差一位十六进制"的伪令牌；形态不合法就不该进入还原流程
        String modelReply = "客户 ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e7361 有问题";

        assertEquals(modelReply, redactor.restore(modelReply));
    }

    @Test
    void legacyV1TokenStillRestoresWhenVaultKnowsIt() {
        // 升级到 128 bit 之后，历史会话里发出去的 v1 令牌不应变成死串
        vault.remember("ID_CARD_3f9a2b7c1d", SensitiveType.ID_CARD, ID_CARD);

        assertEquals("客户 " + ID_CARD + " 命中名单",
                redactor.restore("客户 ID_CARD_3f9a2b7c1d 命中名单"));
    }

    @Test
    void redactionNeverEmitsTheRetiredShortTokenFormat() {
        String redacted = redactor.redact("客户 " + ID_CARD);

        String token = tokenIn(redacted, "ID_CARD_");
        assertTrue(token.startsWith("ID_CARD_v2_"), token);
        assertEquals("ID_CARD_v2_".length() + Pseudonymizer.DIGEST_HEX_LENGTH, token.length(), token);
    }

    @Test
    void vaultCountsDistinctTokensOnly() {
        redactor.redact("客户 " + ID_CARD + " 与 " + ID_CARD);
        assertEquals(1, vault.size());
    }

    @Test
    void disabledTypeIsNeitherRedactedNorRestored() {
        PromptRedactor idCardOnly = new PromptRedactor(
                new Pseudonymizer("secret"), new InMemoryTokenVault(), Set.of(SensitiveType.ID_CARD));

        String redacted = idCardOnly.redact("手机号 " + PHONE + "，证件 " + ID_CARD);

        assertTrue(redacted.contains(PHONE), "未启用的类型不应被处理：" + redacted);
        assertFalse(redacted.contains(ID_CARD), redacted);
    }

    // ---------- 常见书写形态（2026-09-22 补）----------
    //
    // 这一组全是**漏检**回归：下面每一种写法，此前一条都认不出来。
    // 而 `redact()` 在没有命中时是"原样返回"的——所以从调用方看不出来任何异常，
    // 那串数字就原样发给了外部模型、原样进了日志与异常堆栈。
    //
    // 根因是三条正则都只认连写形态：
    //   * 手机号前面挂着 `(?<![0-9])`，`+8613812345678` 里那个 6 就会让整条不匹配；
    //   * 银行卡只认连着的 16 位，而 `6222 0202 0011 2347` 才是卡面上印的写法；
    //   * 15 位老身份证既不在 18 位之内、也不在 16~19 位之内，两道都够不着。

    @Test
    void everyCommonlyWrittenFormIsRedactedAndRestorable() {
        String[] forms = {
                "+8613812345678",       // 国家码带 +
                "8613812345678",        // 国家码不带 +
                "138-1234-5678",        // 连字符分隔
                "138 1234 5678",        // 空格分隔
                "6222 0202 0011 2347",  // 卡面写法：4 位分组
                "110101900307853",      // 15 位老身份证
        };
        for (String raw : forms) {
            String prompt = "客户资料：" + raw + " 请核对";
            String redacted = redactor.redact(prompt);

            assertFalse(redacted.contains(raw), "「" + raw + "」没被脱敏，实际：" + redacted);
            assertEquals(prompt, redactor.restore(redacted),
                    "「" + raw + "」还原后与原文不一致——分隔符也算原文的一部分");
        }
    }

    /** 分隔符只改变"怎么读"，不改变"是什么类型"。 */
    @Test
    void separatorsDoNotChangeTheDetectedType() {
        for (String phone : new String[] {"+8613812345678", "8613812345678", "138-1234-5678", "138 1234 5678"}) {
            assertEquals(SensitiveType.PHONE, PiiDetector.detect(phone, PromptRedactor.DEFAULT_TYPES).get(0).type(), phone);
        }
        assertEquals(SensitiveType.BANK_CARD,
                PiiDetector.detect("6222 0202 0011 2347", PromptRedactor.DEFAULT_TYPES).get(0).type());
        assertEquals(SensitiveType.ID_CARD,
                PiiDetector.detect("110101900307853", PromptRedactor.DEFAULT_TYPES).get(0).type());
    }

    /** 15 位形态没有校验位可用，只能靠出生日期筛——这条挡住"随便 15 位数字都算身份证"。 */
    @Test
    void fifteenDigitFormStillRequiresAPlausibleBirthDate() {
        assertTrue(PiiDetector.isValidLegacyIdCard("110101900307853"));
        assertFalse(PiiDetector.isValidLegacyIdCard("110101902207853"), "月份 22");
        assertFalse(PiiDetector.isValidLegacyIdCard("110101900007853"), "月份 00");
        assertFalse(PiiDetector.isValidLegacyIdCard("11010119900307853"), "16 位不是老身份证");
    }

    /**
     * 比卡号更长的数字串不该被从头截一段出来。
     *
     * <p>老正则 `[0-9]{16,19}` 遇到 20 位数字会截前 16 位去验 Luhn，
     * 而**截出来的那一段恰好通过 Luhn 的概率是 1/10**——也就是说"报不报"
     * 取决于这串数字凑巧长什么样，而不是取决于它是不是卡号。
     */
    @Test
    void aDigitRunLongerThanAnyCardIsNotCarvedIntoACardNumber() {
        String prompt = "流水号 12345678901234567890 已受理";

        assertEquals(prompt, redactor.redact(prompt), "20 位数字不该被当成卡号");
    }

    private static String tokenIn(String text, String prefix) {
        int start = text.indexOf(prefix);
        assertTrue(start >= 0, "文本中未找到令牌前缀 " + prefix + "：" + text);
        int end = start;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(start, end);
    }
}
