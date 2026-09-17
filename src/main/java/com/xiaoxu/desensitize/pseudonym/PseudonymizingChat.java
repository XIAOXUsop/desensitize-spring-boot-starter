package com.xiaoxu.desensitize.pseudonym;

import java.util.Optional;

/**
 * 把脱敏 / 还原套在一次模型调用外面：**出站假名化、调用、入站还原、异常消毒**。
 *
 * <pre>{@code
 * PseudonymizingChat chat = PseudonymizingChat.scoped(scopedRedactor, prompt -> chatModel.chat(prompt));
 * String reply = chat.chat("客户 110101199003078531 的交易是否可疑");   // 拿到的回复里是真实身份
 * }</pre>
 *
 * <h2>它不做什么</h2>
 * <ul>
 *   <li><b>不自动包装应用里所有的模型 Bean。</b>那会改变已有代码的语义，
 *       而且没有人希望自己的模型调用在升级一个依赖之后突然开始改写输入。
 *       要用就显式构造一个。</li>
 *   <li><b>不支持流式。</b>流式的令牌可能被切成两半（{@code ID_CARD_v2_9f2c} 与
 *       {@code 4a1b…}），逐块还原要么漏、要么把半个令牌当正文吐出去。
 *       与其做一个"大部分时候对"的版本，不如明确说不支持：需要流式就自己在
 *       完整分片边界上还原，或者关闭这一层。</li>
 * </ul>
 *
 * <h2>关于异常</h2>
 * 模型 SDK 常把请求内容带进异常 message。所以调用失败时这里会：
 * 给 message 消毒（原文变成 {@code [REDACTED:类型]}），并在本次请求确实含敏感内容时
 * **不保留底层堆栈**——堆栈是最常被打印的东西，挂上去等于把原文放进日志。
 * 底层异常的类型名仍然保留（{@link ModelInvocationException#getOriginalExceptionType()}），
 * 足够区分超时、鉴权失败与别的。请求本身不含敏感内容时不做这个取舍，堆栈照常保留。
 */
public final class PseudonymizingChat {

    private final PromptRedactor redactor;
    private final ChatInvoker invoker;
    private final VaultScope scope;

    private PseudonymizingChat(PromptRedactor redactor, ChatInvoker invoker, VaultScope scope) {
        this.redactor = redactor;
        this.invoker = invoker;
        this.scope = scope;
    }

    /**
     * 构造一个绑定到会话的调用装饰器。
     *
     * <p><b>必须传入绑定过作用域的脱敏器</b>（{@link PromptRedactor#scoped}）：
     * 不绑作用域时令牌是全局的，会话结束时没法只撤销自己那一份——
     * 而这层存在的意义之一就是让"会话结束"这件事在数据上真的发生。
     *
     * @throws IllegalArgumentException 脱敏器没有绑定作用域
     */
    public static PseudonymizingChat scoped(PromptRedactor scopedRedactor, ChatInvoker invoker) {
        if (scopedRedactor == null) {
            throw new IllegalArgumentException("redactor 不能为空");
        }
        if (invoker == null) {
            throw new IllegalArgumentException("invoker 不能为空");
        }
        Optional<VaultScope> scope = scopedRedactor.scope();
        if (scope.isEmpty()) {
            throw new IllegalArgumentException(
                    "必须传入绑定过作用域的脱敏器（PromptRedactor.scoped(...)）："
                            + "不绑作用域时所有会话共用一张映射表，会话结束时无法只撤销自己那一份");
        }
        return new PseudonymizingChat(scopedRedactor, invoker, scope.get());
    }

    /** 本次调用所属的会话作用域 */
    public VaultScope scope() {
        return scope;
    }

    /**
     * 一次完整往返：出站脱敏 → 调用模型 → 入站还原。
     *
     * <p>多轮对话只要复用同一个实例（同一个作用域、同一个保险库），
     * 同一客户在整段会话里就始终是同一个令牌——模型能正确地把上下文关联到同一个人。
     *
     * @return 已经把令牌还原成真实值的回复
     * @throws ModelInvocationException 调用失败；message 已消毒，是否保留堆栈见类型注释
     */
    public String chat(String userPrompt) {
        if (userPrompt == null || userPrompt.isEmpty()) {
            return invoke("", false);
        }
        String redacted = redactor.redact(userPrompt);
        boolean touchedSensitive = !redacted.equals(userPrompt);
        return invoke(redacted, touchedSensitive);
    }

    private String invoke(String redactedPrompt, boolean touchedSensitive) {
        String reply;
        try {
            reply = invoker.chat(redactedPrompt);
        } catch (Exception e) {
            throw toFailure(e, touchedSensitive);
        }
        // 只还原保险库里已知的令牌；模型自己编的"看起来像令牌"的串保持原样
        return redactor.restore(reply == null ? "" : reply);
    }

    private ModelInvocationException toFailure(Exception e, boolean touchedSensitive) {
        String type = e.getClass().getName();
        if (!touchedSensitive) {
            // 请求里本来就没有敏感内容，没有可泄漏的东西——不该白白丢掉堆栈
            ModelInvocationException wrapped = new ModelInvocationException(
                    "模型调用失败：" + type + ": " + e.getMessage(), type, false);
            wrapped.initCause(e);
            return wrapped;
        }
        String sanitized = redactor.sanitizeForLog(String.valueOf(e.getMessage()));
        return new ModelInvocationException(
                "模型调用失败：" + e.getClass().getSimpleName() + ": " + sanitized
                        + "（本次请求含敏感内容，底层异常的堆栈未保留——堆栈会被打印，"
                        + "挂上去等于把原文放进日志）",
                type, true);
    }
}
