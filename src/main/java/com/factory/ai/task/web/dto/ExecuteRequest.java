package com.factory.ai.task.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 任务执行请求体。
 *
 * @param userId worker 的用户 ID，必须为正数
 * @param repo   目标仓库名，不能为空
 */
public record ExecuteRequest(
    @NotNull @Positive Long userId,
    @NotBlank String repo) {}
