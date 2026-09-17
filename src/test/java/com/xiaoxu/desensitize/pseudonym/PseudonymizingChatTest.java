package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用装饰器：出站脱敏、入站还原、异常消毒。
 *
 * <p>用假模型而不是真模型：这里要验的全是**这一层**的行为（模型看到什么、异常里留下什么），
 * 真模型只会让结果不确定，且把测试变成需要网络与密钥的东西。
 */
class PseudonymizingChatTest {

    private static final String ID_CARD = "110101199003078531";
    /** 另一个**校验位合法**的身份证——校验位不合法的值纯靠值形态识别不出来，
     *  拿它当夹具会得到一个看不出问题的空断言 */
    private static final String OTHER_ID_CARD = "110101198506123459";
    private static final VaultScope SESSION = VaultScope.of("chat-session-1");

    private final InMemoryTokenVault vault = new InMemoryTokenVault();
    private final Pseudonymizer pseudonymizer = new Pseudonymizer("unit-test-secret");
    private final PromptRedactor redactor = PromptRedactor.scoped(pseudonymizer, vault, SESSION,
            Set.of(SensitiveType.ID_CARD));

    /** 假模型：记录它实际收到的提示词，并按脚本回复 */
    private static class FakeModel implements ChatInvoker {

        private final List<String> promptsSeen = new ArrayList<>();
        private final java.util.function.UnaryOperator<String> reply;

        private FakeModel(java.util.function.UnaryOperator<String> reply) {
            this.reply = reply;
        }

        @Override
        public String chat(String prompt) {
            promptsSeen.add(prompt);
            return reply.apply(prompt);
        }
    }

    // ---------- 往返 ----------

    @Test
    void modelNeverSeesThePlaintextAndTheCallerGetsItBack() {
        FakeModel model = new FakeModel(prompt -> "客户 " + tokenIn(prompt) + " 近 3 月有 14 笔等额存取");
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        String reply = chat.chat("客户 " + ID_CARD + " 的交易是否可疑");

        assertFalse(model.promptsSeen.get(0).contains(ID_CARD), "模型不该看到原文");
        assertTrue(model.promptsSeen.get(0).contains("ID_CARD_v2_"), model.promptsSeen.get(0));
        assertTrue(reply.contains(ID_CARD), "调用方拿到的应该是真实身份：" + reply);
    }

    @Test
    void sameCustomerKeepsTheSameTokenAcrossTurns() {
        FakeModel model = new FakeModel(prompt -> tokenIn(prompt));
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        chat.chat("客户 " + ID_CARD + " 本月是否正常");
        chat.chat("这位客户 " + ID_CARD + " 上月呢");

        assertEquals(2, model.promptsSeen.size());
        assertEquals(tokenIn(model.promptsSeen.get(0)), tokenIn(model.promptsSeen.get(1)),
                "多轮对话里模型必须能认出是同一个人");
    }

    @Test
    void unknownTokensInTheReplyAreLeftAlone() {
        FakeModel model = new FakeModel(prompt -> "客户 ID_CARD_v2_00000000000000000000000000000000 疑似命中名单");
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        String reply = chat.chat("客户 " + ID_CARD);

        // 模型自己编的令牌不在保险库里，保持原样而不是猜着替换
        assertTrue(reply.contains("ID_CARD_v2_00000000000000000000000000000000"), reply);
    }

    @Test
    void promptWithoutSensitiveDataIsPassedThroughUnchanged() {
        FakeModel model = new FakeModel(prompt -> prompt);
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        String prompt = "本月交易笔数较上月上升 12%，是否需要关注？";

        assertEquals(prompt, chat.chat(prompt));
        assertEquals(prompt, model.promptsSeen.get(0));
    }

    @Test
    void nullAndEmptyPromptsAreHandledWithoutTouchingTheModel() {
        FakeModel model = new FakeModel(prompt -> "ok");
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        assertEquals("ok", chat.chat(null));
        assertEquals("ok", chat.chat(""));
        assertEquals(List.of("", ""), model.promptsSeen);
    }

    // ---------- 异常路径 ----------

    @Test
    void failedCallSanitizesTheMessageAndDropsTheStack() {
        // 模拟"某处把原始请求内容带进了异常 message"——SDK、HTTP 客户端、自研重试包装
        // 都可能这么干。装饰器要能兜住这种情况，而不只是兜住它自己发出去的那份。
        List<String> seen = new ArrayList<>();
        ChatInvoker model = prompt -> {
            seen.add(prompt);
            throw new IllegalStateException("upstream echoed: 客户 " + ID_CARD + " 的交易");
        };
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        ModelInvocationException failure = assertThrows(ModelInvocationException.class,
                () -> chat.chat("客户 " + ID_CARD + " 的交易"));

        assertFalse(failure.getMessage().contains(ID_CARD), failure.getMessage());
        assertTrue(failure.getMessage().contains("[REDACTED:ID_CARD]"), failure.getMessage());
        assertTrue(failure.isCauseOmitted());
        assertNull(failure.getCause(), "含敏感内容的请求不该把原始堆栈挂上去");
        assertEquals("java.lang.IllegalStateException", failure.getOriginalExceptionType());
        assertEquals(1, seen.size());
    }

    @Test
    void failureWithoutSensitiveContentKeepsTheStackTrace() {
        FakeModel model = new FakeModel(prompt -> {
            throw new IllegalStateException("connection reset");
        });
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        ModelInvocationException failure = assertThrows(ModelInvocationException.class,
                () -> chat.chat("本月交易笔数上升 12%，是否关注？"));

        assertFalse(failure.isCauseOmitted(), "请求里没有敏感内容，没有理由丢掉堆栈");
        assertNotNull(failure.getCause());
        assertEquals("IllegalStateException", failure.getCause().getClass().getSimpleName());
    }

    @Test
    void checkedExceptionsFromTheInvokerAreWrappedToo() {
        ChatInvoker failing = prompt -> {
            throw new java.io.IOException("boom");
        };
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, failing);

        ModelInvocationException failure = assertThrows(ModelInvocationException.class,
                () -> chat.chat("客户 " + ID_CARD));

        assertEquals("java.io.IOException", failure.getOriginalExceptionType());
    }

    // ---------- 使用方式 ----------

    @Test
    void anUnscopedRedactorIsRejected() {
        PromptRedactor unscoped = new PromptRedactor(pseudonymizer, vault);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PseudonymizingChat.scoped(unscoped, prompt -> "ok"));

        assertTrue(failure.getMessage().contains("作用域"), failure.getMessage());
    }

    @Test
    void decoratorCarriesTheSessionScopeAndLeavesTheUnderlyingInvokerAlone() {
        FakeModel model = new FakeModel(prompt -> "直接调用不受影响");
        PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, model);

        assertEquals(SESSION, chat.scope());
        // 装饰器只在显式调用时介入；底层那个 ChatInvoker 本身行为不变
        assertEquals("直接调用不受影响", model.chat("客户 " + ID_CARD));
        assertEquals("客户 " + ID_CARD, model.promptsSeen.get(0));
    }

    @Test
    void logSanitizerReplacesValuesWithoutRegisteringThem() {
        String log = "失败：客户 " + OTHER_ID_CARD + " 校验未通过";

        String sanitized = redactor.sanitizeForLog(log);

        assertEquals("失败：客户 [REDACTED:ID_CARD] 校验未通过", sanitized);
        // 关键：日志消毒不该往保险库里塞东西——否则只在日志里出现过的值
        // 会变成一枚可还原的令牌
        assertTrue(vault.original(pseudonymizer.tokenize(SensitiveType.ID_CARD, OTHER_ID_CARD)).isEmpty());
    }

    @Test
    void logSanitizerLeavesCleanTextAlone() {
        String clean = "本月交易笔数较上月上升 12%";

        assertEquals(clean, redactor.sanitizeForLog(clean));
    }

    private static String tokenIn(String text) {
        int start = text.indexOf("ID_CARD_v2_");
        assertTrue(start >= 0, "文本里没有令牌：" + text);
        int end = start;
        while (end < text.length()
                && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(start, end);
    }
}
