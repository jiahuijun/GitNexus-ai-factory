package com.factory.ai.chat.web.dto;

/**
 * 开始会话响应体。
 *
 * @param sessionId     新会话的 ID
 * @param firstQuestion LLM 生成的第一个澄清问题
 * @param ready         是否已收集足够信息（首轮通常为 false）
 */
public record StartSessionResponse(String sessionId, String firstQuestion, boolean ready) {}
