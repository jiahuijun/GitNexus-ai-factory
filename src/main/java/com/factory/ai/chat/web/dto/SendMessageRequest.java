package com.factory.ai.chat.web.dto;

/**
 * 发送消息请求体。
 *
 * @param text 用户输入的回答文本
 */
public record SendMessageRequest(String text) {}
