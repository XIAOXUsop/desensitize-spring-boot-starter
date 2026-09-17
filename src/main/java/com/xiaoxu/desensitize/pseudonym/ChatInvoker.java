package com.xiaoxu.desensitize.pseudonym;

/**
 * 真正调用大模型的那一步。
 *
 * <p>把它抽成一个函数式接口而不是直接依赖某个 SDK：本模块（`desensitize-spring-boot-starter`）
 * 的运行时依赖只有 Jackson，不该为了一个装饰器把 LangChain4j 拖进来。
 * 用 LangChain4j 的话接一行就够：
 *
 * <pre>{@code
 * ChatInvoker invoker = prompt -> chatModel.chat(prompt);
 * }</pre>
 *
 * <p>实现里**不要**自己打印 prompt：出站的那份已经脱敏，但异常路径上的日志仍然可能在
 * 别处留下原文——装饰器能守住自己的边界，守不住你的日志语句。
 */
@FunctionalInterface
public interface ChatInvoker {

    /**
     * 发一次请求并返回模型的文本回复。
     *
     * @param prompt 已经脱敏过的提示词
     * @throws Exception 任何调用失败。装饰器会把异常转成
     *                   {@link ModelInvocationException} 并处理其中的敏感内容
     */
    String chat(String prompt) throws Exception;
}
