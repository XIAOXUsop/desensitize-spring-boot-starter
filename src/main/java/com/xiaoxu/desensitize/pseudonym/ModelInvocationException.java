package com.xiaoxu.desensitize.pseudonym;

/**
 * 模型调用失败，且失败信息已经过脱敏处理。
 *
 * <p>存在的理由只有一个：**异常会进日志，日志会进各种系统**。
 * 模型 SDK 抛异常时经常把请求内容原样带进 message（"invalid request: {…客户身份证…}"），
 * 于是脱敏在传输链路上做的一切，都会在错误处理这一步被原样吐回去。
 *
 * <p>所以这个异常遵守两条规则：
 * <ul>
 *   <li>自己的 message 经过脱敏——原文在里面的部分会变成令牌；</li>
 *   <li>若这次请求确实脱敏过内容，则**不挂原始异常**（{@code getCause()} 为 null）。
 *       挂上它就等于把未脱敏的原文放进了堆栈里，而堆栈是最常被打印的东西。
 *       代价是丢失底层堆栈——这个代价是刻意付的，见 {@link #getCause()}。</li>
 * </ul>
 *
 * <p>请求里本来就没有敏感内容时不套用第二条：没有可泄漏的东西，就不该白白丢掉堆栈。
 */
public class ModelInvocationException extends RuntimeException {

    private final String originalExceptionType;

    private final boolean causeOmitted;

    ModelInvocationException(String message, String originalExceptionType, boolean causeOmitted) {
        super(message);
        this.originalExceptionType = originalExceptionType;
        this.causeOmitted = causeOmitted;
    }

    /** 底层异常的类型名——堆栈被丢弃时，至少还能知道是超时、鉴权失败还是别的 */
    public String getOriginalExceptionType() {
        return originalExceptionType;
    }

    /**
     * 是否因为"这次请求含敏感内容"而丢弃了底层异常。
     *
     * <p>为 true 时 {@link #getCause()} 一定是 null，且这是**预期行为**不是 bug。
     */
    public boolean isCauseOmitted() {
        return causeOmitted;
    }
}
