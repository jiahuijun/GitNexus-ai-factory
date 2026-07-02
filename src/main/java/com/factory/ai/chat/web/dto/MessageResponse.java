package com.factory.ai.chat.web.dto;

/**
 * 发送消息响应体。
 *
 * @param reply LLM 的回复文本
 * @param ready 是否已收集足够信息可以拆解
 */
public record MessageResponse(String reply, boolean ready) {}
