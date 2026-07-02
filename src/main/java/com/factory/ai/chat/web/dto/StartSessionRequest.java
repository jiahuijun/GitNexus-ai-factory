package com.factory.ai.chat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 开始澄清会话请求体。
 *
 * @param requirement 原始需求文本，不能为空
 * @param repo        目标仓库名，不能为空
 * @param adminId     管理员 ID，必须为正数
 */
public record StartSessionRequest(
    @NotBlank String requirement,
    @NotBlank String repo,
    @NotNull @Positive Long adminId) {}
