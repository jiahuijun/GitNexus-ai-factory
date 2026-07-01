package com.factory.ai.task.service;

/**
 * LLM 调用失败时抛出的运行时异常。
 *
 * <p>在 AI Factory 的"不降级"原则下，当 LLM 网关（如 {@link SpringAiLlmGateway}）
 * 调用大模型拆解任务失败时，抛出此异常以触发 {@code @Transactional} 回滚，
 * 避免产生半成品任务数据。使用非受检异常是为了不污染业务方法签名。</p>
 *
 * @see SpringAiLlmGateway
 */
public class LlmException extends RuntimeException {

    /**
     * 使用错误消息构造异常。
     *
     * @param message 描述 LLM 调用失败原因的消息（通常包含触发失败的需求文本）
     */
    public LlmException(String message) { super(message); }

    /**
     * 使用错误消息和根因异常构造异常。
     *
     * @param message 描述 LLM 调用失败原因的消息
     * @param cause   底层抛出的原始异常（如 Spring AI 的 ChatClient 异常、超时、反序列化失败等）
     */
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
