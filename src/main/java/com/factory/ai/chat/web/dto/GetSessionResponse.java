package com.factory.ai.chat.web.dto;

import com.factory.ai.chat.session.ChatMessage;

import java.util.List;

/**
 * 会话状态响应体（用于恢复对话界面）。
 *
 * @param sessionId     会话 ID
 * @param originalRequirement 原始需求
 * @param history       对话历史
 * @param ready         是否已澄清
 * @param state         会话状态（CHAT | DECOMPOSED）
 * @param taskId        已拆解的任务 ID（state=DECOMPOSED 时有值）
 */
public record GetSessionResponse(
    String sessionId,
    String originalRequirement,
    List<ChatMessage> history,
    boolean ready,
    String state,
    Long taskId
) {}
