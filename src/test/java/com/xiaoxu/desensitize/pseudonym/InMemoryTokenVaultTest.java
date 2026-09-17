package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌碰撞必须失败——而不是"两个原文共用一个令牌"。
 *
 * <p>这里不去论证"随机测试一百万次没有碰撞"（那只能证明这一次没撞，证明不了位数够），
 * 而是直接构造"同一令牌、两段不同原文"这个状态，验证保险库的反应。
 * 位数是否足够是设计问题，由 {@link PseudonymizerTest} 的格式断言负责。
 */
class InMemoryTokenVaultTest {

    private static final String RAW_A = "110101199003078531";
    private static final String RAW_B = "110101199003078532";
    private static final String TOKEN = "ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618";

    private final InMemoryTokenVault vault = new InMemoryTokenVault();

    @Test
    void sameTokenSameValueIsIdempotent() {
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);

        assertEquals(1, vault.size());
        assertEquals(Optional.of(RAW_A), vault.original(TOKEN));
    }

    @Test
    void sameTokenDifferentValueFailsLoudly() {
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);

        TokenCollisionException thrown = assertThrows(TokenCollisionException.class,
                () -> vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_B));

        // 错误必须指名道姓，否则运维拿到一句"出错了"无从下手
        assertEquals(SensitiveType.ID_CARD, thrown.getType());
        assertEquals(TOKEN, thrown.getToken());
    }

    @Test
    void collisionKeepsTheOriginalMappingSoNothingSilentlyChanges() {
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);
        assertThrows(TokenCollisionException.class,
                () -> vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_B));

        // 关键：碰撞被拒绝后，令牌仍只指向第一个原文。
        // 若实现改成覆盖，历史对话里引用该令牌的地方会集体指向另一个人。
        assertEquals(Optional.of(RAW_A), vault.original(TOKEN));
        assertEquals(1, vault.size());
    }

    @Test
    void collisionMessageLeaksNoPlaintext() {
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);

        String message = assertThrows(TokenCollisionException.class,
                () -> vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_B)).getMessage();

        // 异常信息会被写进日志，不能因为一次碰撞就把两个真实身份证号摊在两个日志系统里
        assertFalse(message.contains(RAW_A), message);
        assertFalse(message.contains(RAW_B), message);
        // 但要留下足以定位的线索
        assertTrue(message.contains(TOKEN), message);
        assertTrue(message.contains(SensitiveType.ID_CARD.name()), message);
    }

    @Test
    void nullArgumentsAreIgnoredRatherThanStored() {
        vault.remember(null, SensitiveType.ID_CARD, RAW_A);
        vault.remember(TOKEN, SensitiveType.ID_CARD, null);

        assertEquals(0, vault.size());
        assertEquals(Optional.empty(), vault.original(TOKEN));
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        assertEquals(Optional.empty(), vault.original(TOKEN));
        assertEquals(Optional.empty(), vault.original(null));
    }

    @Test
    void clearForgetsEverything() {
        vault.remember(TOKEN, SensitiveType.ID_CARD, RAW_A);

        vault.clear();

        assertEquals(0, vault.size());
        assertEquals(Optional.empty(), vault.original(TOKEN));
    }

    @Test
    void redactFailsInsteadOfHandingOutATokenThatRestoresToSomeoneElse() {
        Pseudonymizer pseudonymizer = new Pseudonymizer("secret");
        PromptRedactor redactor = new PromptRedactor(pseudonymizer, vault);

        // 保险库中该令牌已经指向另一个人（例如密钥被换过、或旧数据残留）
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, RAW_A);
        vault.remember(token, SensitiveType.ID_CARD, RAW_B);

        // 此时若照常出站，模型会看到同一个令牌，而回程会被还原成 RAW_B —— 属于把 A 的业务结论记到 B 头上
        assertThrows(TokenCollisionException.class,
                () -> redactor.redact("客户 " + RAW_A + " 本月交易异常"));

        assertEquals(Optional.of(RAW_B), vault.original(token));
    }
}
