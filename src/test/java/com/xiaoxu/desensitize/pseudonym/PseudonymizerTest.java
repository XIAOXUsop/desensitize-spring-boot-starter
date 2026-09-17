package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌格式与版本解析测试。
 *
 * <p>重点不是"令牌长什么样好看"，而是：位数是否真的够、历史令牌是否还认得出来、
 * 形似令牌的垃圾串是否会被误判。后两者决定了还原环节会不会把错误的东西替换进正文。
 */
class PseudonymizerTest {

    private static final String ID_CARD = "110101199003078531";
    private static final String HEX_32 = "9f2c4a1b7e3d5086c1a4f0b2d9e73618";

    private final Pseudonymizer pseudonymizer = new Pseudonymizer("unit-test-secret");

    // ---------- 生成格式 ----------

    @Test
    void tokenCarriesTypeAndCurrentVersion() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);

        assertTrue(token.startsWith("ID_CARD_v2_"), token);
        assertEquals("ID_CARD_v2_".length() + Pseudonymizer.DIGEST_HEX_LENGTH, token.length(), token);
    }

    @Test
    void digestIs128BitNotTheOld40Bit() {
        String digest = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD)
                .substring("ID_CARD_v2_".length());

        // 40 bit（10 位十六进制）在百万级明文下约有 50% 碰撞概率，不足以支撑真实客户量级
        assertEquals(32, digest.length(), digest);
        assertEquals(128, digest.length() * 4);
        assertTrue(digest.matches("[0-9a-f]{32}"), digest);
    }

    @Test
    void tokenIsDeterministic() {
        assertEquals(pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD),
                pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD));
    }

    @Test
    void differentSecretsProduceDifferentTokens() {
        assertNotEquals(new Pseudonymizer("secret-a").tokenize(SensitiveType.ID_CARD, ID_CARD),
                new Pseudonymizer("secret-b").tokenize(SensitiveType.ID_CARD, ID_CARD));
    }

    @Test
    void sameDigitsUnderDifferentTypesGetDifferentTokens() {
        assertNotEquals(pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD),
                pseudonymizer.tokenize(SensitiveType.BANK_CARD, ID_CARD));
    }

    @Test
    void tokenContainsNoOriginalValue() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);

        assertFalse(token.contains(ID_CARD), token);
    }

    // ---------- 形态校验：v2 ----------

    @Test
    void acceptsWellFormedV2Token() {
        assertTrue(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v2_" + HEX_32));
        assertTrue(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD,
                pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD)));
    }

    @Test
    void rejectsTokenOfAnotherType() {
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.BANK_CARD, "ID_CARD_v2_" + HEX_32));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "BANK_CARD_v2_" + HEX_32));
    }

    @Test
    void rejectsUnknownVersionRatherThanTreatingItAsOurs() {
        // 未来版本可能改变摘要口径，按当前规则解析会得出错误结论，因此不认
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v3_" + HEX_32));
        assertNull(Pseudonymizer.tokenVersionOf(SensitiveType.ID_CARD, "ID_CARD_v3_" + HEX_32));
    }

    @Test
    void rejectsWrongDigestLength() {
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v2_" + HEX_32.substring(1)));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v2_" + HEX_32 + "a"));
    }

    @Test
    void rejectsNonHexDigest() {
        // 模型容易编出"看起来像令牌"的串，比如把 o 和 0、l 和 1 混用；这类一律不认
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD,
                "ID_CARD_v2_" + "z".repeat(32)));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD,
                "ID_CARD_v2_" + HEX_32.substring(0, 31).toUpperCase() + "A"));
    }

    @Test
    void rejectsMissingOrEmptyVersionSegment() {
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD__" + HEX_32));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v_" + HEX_32));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_v2" + HEX_32));
    }

    @Test
    void rejectsNullOrUnrelatedText() {
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, null));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, ""));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "110101199003078531"));
    }

    // ---------- 形态校验：v1（历史格式） ----------

    @Test
    void stillRecognisesLegacyV1TokenForRestore() {
        // v1 不再生成，但历史数据仍要能还原，否则升级会把已有会话里的令牌变成死串
        assertTrue(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_3f9a2b7c1d"));
        assertEquals(Integer.valueOf(Pseudonymizer.LEGACY_V1_VERSION),
                Pseudonymizer.tokenVersionOf(SensitiveType.ID_CARD, "ID_CARD_3f9a2b7c1d"));
    }

    @Test
    void legacyTokenMustBeExactly10HexChars() {
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_3f9a2b7c1"));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_3f9a2b7c1de"));
        assertFalse(Pseudonymizer.isTokenOf(SensitiveType.ID_CARD, "ID_CARD_3f9a2b7c1z"));
    }

    @Test
    void v1ShapeDoesNotSwallowPartOfALongerV2Token() {
        String v2 = "ID_CARD_v2_" + HEX_32;

        // v1 规则是"前缀 + 恰好 10 位十六进制"，v2 里紧跟前缀的是 'v'，不该被切出半截
        assertNull(Pseudonymizer.tokenVersionOf(SensitiveType.ID_CARD, v2.substring(0, 18)));
        assertEquals(Integer.valueOf(Pseudonymizer.CURRENT_VERSION),
                Pseudonymizer.tokenVersionOf(SensitiveType.ID_CARD, v2));
    }

    // ---------- 还原用正则 ----------

    @Test
    void tokenPatternMatchesBothVersionsAndNothingElse() {
        String v2Token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        String text = "v2=" + v2Token
                + " v1=ID_CARD_3f9a2b7c1d 太短=ID_CARD_abc 别类型=BANK_CARD_v2_" + HEX_32;

        Matcher matcher = Pseudonymizer.tokenPattern(SensitiveType.ID_CARD).matcher(text);

        assertEquals(v2Token, nextMatch(matcher));
        assertEquals("ID_CARD_3f9a2b7c1d", nextMatch(matcher));
        assertFalse(matcher.find(), "不应再匹配到任何内容：" + text);
    }

    @Test
    void tokenPatternDoesNotMatchInsideALongerWord() {
        // 前面粘着字母数字时不是独立令牌，替换它会破坏原始文本
        Matcher matcher = Pseudonymizer.tokenPattern(SensitiveType.ID_CARD)
                .matcher("xID_CARD_v2_" + HEX_32);

        assertFalse(matcher.find());
    }

    @Test
    void tokenPatternIsUsableForTypesWithUnderscoreInName() {
        String token = pseudonymizer.tokenize(SensitiveType.BANK_CARD, "4539578763621486");

        assertTrue(Pseudonymizer.tokenPattern(SensitiveType.BANK_CARD).matcher(token).matches(), token);
    }

    private static String nextMatch(Matcher matcher) {
        assertTrue(matcher.find(), "预期还有匹配项");
        String value = matcher.group();
        assertNotNull(value);
        return value;
    }
}
