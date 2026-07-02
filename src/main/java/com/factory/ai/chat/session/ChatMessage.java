package com.factory.ai.chat.session;

/**
 * 对话消息记录，用于澄清会话的对话历史。
 *
 * @param role 消息角色："user"（用户）或 "assistant"（LLM 回复）
 * @param text 消息文本内容
 */
public record ChatMessage(String role, String text) {}
