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
