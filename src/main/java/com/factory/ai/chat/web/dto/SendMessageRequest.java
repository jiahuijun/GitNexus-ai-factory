package com.factory.ai.chat.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发送消息请求体。
 *
 * @param text 用户输入的回答文本，不能为空
 */
public record SendMessageRequest(@NotBlank String text) {}
