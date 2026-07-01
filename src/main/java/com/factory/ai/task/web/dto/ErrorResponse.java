package com.factory.ai.task.web.dto;

/**
 * 标准错误响应体，用于上游（GitNexus / LLM）失败时返回给调用方。
 *
 * <p>对应 HTTP 503 Service Unavailable，遵循「不降级」策略：以结构化 code + message
 * 明确告知调用方上游依赖不可用，便于其重试或熔断。
 *
 * @param code    错误码，如 {@code UPSTREAM_UNAVAILABLE}
 * @param message 人类可读的错误说明，通常为底层异常的 message
 */
public record ErrorResponse(String code, String message) {}
