package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级令牌保险库：隔离、清理、过期、审计。
 *
 * <p>没有作用域时整张「令牌 → 原文」表是全局的：会话结束想清掉自己的映射只能清掉整张表，
 * 两个会话的令牌空间混在一起互相影响，也没法回答"这个令牌属于哪次会话"。
 *
 * <p>这里还要守住一个**不该被误以为有**的性质：作用域隔离的是映射表，
 * 不是令牌本身。令牌仍然是确定性的——同一段原文在不同会话里依然是同一个令牌串，
 * 那是跨轮次一致性的来源，不是缺陷。把它写进测试，是为了防止以后有人
 * "顺手"把作用域掺进摘要输入，把跨轮次一致性悄悄改掉。
 */
class ScopedTokenVaultTest {

    private static final String ID_CARD = "110101199003078531";
    private static final String OTHER_ID_CARD = "110101199003078532";

    private static final VaultScope SESSION_A = VaultScope.of("session-a");
    private static final VaultScope SESSION_B = VaultScope.of("session-b");

    private final InMemoryTokenVault vault = new InMemoryTokenVault();
    private final Pseudonymizer pseudonymizer = new Pseudonymizer("unit-test-secret");

    // ---------- 隔离 ----------

    @Test
    void scopesDoNotSeeEachOthersMappings() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);

        assertEquals(Optional.of(ID_CARD), vault.original(SESSION_A, token));
        // A 登记过不代表 B 能用——B 里这个令牌根本不存在
        assertEquals(Optional.empty(), vault.original(SESSION_B, token));
    }

    @Test
    void sizeCountsWithinItsOwnScopeOnly() {
        String tokenA = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        String tokenB = pseudonymizer.tokenize(SensitiveType.PHONE, "13812345678");
        vault.remember(SESSION_A, tokenA, SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, tokenB, SensitiveType.PHONE, "13812345678");
        vault.remember(SESSION_B, tokenA, SensitiveType.ID_CARD, ID_CARD);

        assertEquals(2, vault.size(SESSION_A));
        assertEquals(1, vault.size(SESSION_B));
        assertEquals(0, vault.size(VaultScope.of("session-c")));
    }

    @Test
    void forgetOnlyAffectsTheTargetScope() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_B, token, SensitiveType.ID_CARD, ID_CARD);

        vault.forget(SESSION_A);

        assertEquals(Optional.empty(), vault.original(SESSION_A, token), "会话 A 的映射应已被删除");
        assertEquals(Optional.of(ID_CARD), vault.original(SESSION_B, token), "删 A 不该动到 B");
        assertEquals(0, vault.size(SESSION_A));
        assertEquals(1, vault.size(SESSION_B));
    }

    @Test
    void forgettingAnUnknownScopeIsHarmless() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);

        vault.forget(VaultScope.of("never-existed"));

        assertEquals(1, vault.size(SESSION_A));
    }

    /**
     * 作用域名是调用方给的，内部拼键时用可打印字符作分隔符会串味：
     * `a` + `b_c` 与 `a_b` + `c` 会拼成同一个键，两个会话就此互相看见。
     */
    @Test
    void scopeNamesCannotBeCraftedToCollide() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(VaultScope.of("s"), token, SensitiveType.ID_CARD, ID_CARD);

        // 用下划线拼键时的经典歧义：`s` + `ID_CARD_v2_x` 与 `s_ID_CARD` + `v2_x`
        // 会拼成同一个键，两个会话就此互相看见。分隔符换成 NUL 之后这条路走不通。
        assertEquals(Optional.empty(),
                vault.original(VaultScope.of("s_ID_CARD"), "v2_" + "0".repeat(32)));
        assertEquals(0, vault.size(VaultScope.of("s_ID_CARD")));
    }

    // ---------- 令牌本身仍然是确定性的 ----------

    @Test
    void scopesDoNotChangeTheTokenSoCrossTurnConsistencySurvives() {
        String tokenA = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        String tokenB = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);

        // 作用域隔离的是映射表，不是令牌；同一原文在任何会话里都是同一个令牌串
        assertEquals(tokenA, tokenB);
    }

    // ---------- 碰撞检测按作用域独立 ----------

    @Test
    void collisionIsDetectedWithinAScope() {
        vault.remember(SESSION_A, "ID_CARD_v2_" + "0".repeat(32), SensitiveType.ID_CARD, ID_CARD);

        assertThrows(TokenCollisionException.class,
                () -> vault.remember(SESSION_A, "ID_CARD_v2_" + "0".repeat(32), SensitiveType.ID_CARD, OTHER_ID_CARD));
    }

    @Test
    void collisionCheckDoesNotSpillAcrossScopes() {
        String shared = "ID_CARD_v2_" + "0".repeat(32);
        vault.remember(SESSION_A, shared, SensitiveType.ID_CARD, ID_CARD);

        // B 里同一个令牌指向别的原文是允许的：两个会话的令牌空间本来就该互相独立
        vault.remember(SESSION_B, shared, SensitiveType.ID_CARD, OTHER_ID_CARD);

        assertEquals(Optional.of(ID_CARD), vault.original(SESSION_A, shared));
        assertEquals(Optional.of(OTHER_ID_CARD), vault.original(SESSION_B, shared));
    }

    // ---------- 过期 ----------

    @Test
    void expiredEntryCannotBeRestored() {
        Instant start = Instant.parse("2026-09-18T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryTokenVault expiring = new InMemoryTokenVault(Duration.ofMinutes(30), clock);
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        expiring.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);

        assertEquals(Optional.of(ID_CARD), expiring.original(SESSION_A, token));

        clock.advance(Duration.ofMinutes(31));

        assertEquals(Optional.empty(), expiring.original(SESSION_A, token), "过期之后令牌应变成死串");
        assertEquals(0, expiring.size(SESSION_A), "过期条目不该继续计入容量");
    }

    @Test
    void expiredTokenCanBeReRegisteredWithADifferentValue() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        InMemoryTokenVault expiring = new InMemoryTokenVault(Duration.ofMinutes(5), clock);
        String shared = "ID_CARD_v2_" + "0".repeat(32);
        expiring.remember(SESSION_A, shared, SensitiveType.ID_CARD, ID_CARD);

        clock.advance(Duration.ofMinutes(6));

        // 过期条目不再是"已登记的原文"，覆盖它是正确行为，不该误报碰撞
        expiring.remember(SESSION_A, shared, SensitiveType.ID_CARD, OTHER_ID_CARD);

        assertEquals(Optional.of(OTHER_ID_CARD), expiring.original(SESSION_A, shared));
    }

    @Test
    void nonPositiveTtlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTokenVault(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTokenVault(Duration.ofSeconds(-1)));
    }

    // ---------- 审计 ----------

    @Test
    void auditSeesScopeAndTypeButNeverThePlaintext() {
        List<String> seen = new ArrayList<>();
        vault.auditWith((scope, type) -> seen.add(scope + "/" + type));
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);

        vault.original(SESSION_A, token);
        vault.original(SESSION_B, token); // 取不到，不该记审计

        assertEquals(List.of("session-a/ID_CARD"), seen);
        assertTrue(seen.stream().noneMatch(s -> s.contains(ID_CARD)), "审计内容里不该出现原文");
    }

    @Test
    void auditIsSilentByDefault() {
        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        vault.remember(SESSION_A, token, SensitiveType.ID_CARD, ID_CARD);

        // 不注册回调时不该抛异常，也不该有任何副作用
        assertEquals(Optional.of(ID_CARD), vault.original(SESSION_A, token));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentRememberAndRestoreIsSafe() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                VaultScope scope = VaultScope.of("session-" + t);
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String raw = "11010119900307" + String.format("%04d", i);
                        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, raw);
                        vault.remember(scope, token, SensitiveType.ID_CARD, raw);
                        if (!vault.original(scope, token).filter(raw::equals).isPresent()) {
                            mismatches.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发用例超时");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, mismatches.get(), "并发下还原出了错误的值");
        for (int t = 0; t < threads; t++) {
            assertEquals(perThread, vault.size(VaultScope.of("session-" + t)));
        }
    }

    // ---------- 端到端：会话级脱敏器 ----------

    @Test
    void scopedRedactorForgetsOnlyItsOwnSession() {
        PromptRedactor sessionA = PromptRedactor.scoped(pseudonymizer, vault, SESSION_A,
                Set.of(SensitiveType.ID_CARD));
        PromptRedactor sessionB = PromptRedactor.scoped(pseudonymizer, vault, SESSION_B,
                Set.of(SensitiveType.ID_CARD));

        String redactedA = sessionA.redact("客户 " + ID_CARD + " 交易异常");
        String redactedB = sessionB.redact("客户 " + ID_CARD + " 交易异常");

        // 两个会话拿到的令牌串是同一个（确定性），但各自登记在各自的表里
        assertEquals(sessionA.restore(redactedA), "客户 " + ID_CARD + " 交易异常");
        assertEquals(sessionB.restore(redactedB), "客户 " + ID_CARD + " 交易异常");

        vault.forget(SESSION_A);

        String token = pseudonymizer.tokenize(SensitiveType.ID_CARD, ID_CARD);
        assertFalse(sessionA.restore(token).contains(ID_CARD), "A 已撤销，不该还能还原");
        assertEquals(ID_CARD, sessionB.restore(token), "撤销 A 不该影响 B");
    }

    @Test
    void scopedRedactorRequiresAScopeAndAScopedVault() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptRedactor.scoped(pseudonymizer, vault, null, Set.of(SensitiveType.ID_CARD)));
        assertThrows(IllegalArgumentException.class,
                () -> PromptRedactor.scoped(pseudonymizer, null, SESSION_A, Set.of(SensitiveType.ID_CARD)));
    }

    @Test
    void unscopedRedactorStillWorksOnTheGlobalScope() {
        PromptRedactor plain = new PromptRedactor(pseudonymizer, vault);

        String redacted = plain.redact("客户 " + ID_CARD);

        assertTrue(plain.scope().isEmpty());
        assertEquals("客户 " + ID_CARD, plain.restore(redacted));
        assertEquals(1, vault.size(VaultScope.GLOBAL));
    }

    @Test
    void scopeRejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> VaultScope.of(""));
        assertThrows(IllegalArgumentException.class, () -> VaultScope.of("   "));
        assertThrows(IllegalArgumentException.class, () -> VaultScope.of(null));
    }

    /** 可手动推进的时钟，让过期行为可以被确定性验证 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
