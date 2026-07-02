package com.factory.ai.chat.web.dto;

/**
 * 开始澄清会话请求体。
 *
 * @param requirement 原始需求文本
 * @param repo        目标仓库名
 * @param adminId     管理员 ID
 */
public record StartSessionRequest(String requirement, String repo, Long adminId) {}
