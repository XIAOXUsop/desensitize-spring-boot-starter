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
