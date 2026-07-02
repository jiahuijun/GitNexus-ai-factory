package com.factory.ai.task.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 任务完成（complete）请求体。
 *
 * <p>携带 worker 身份与目标仓库地址，用于在 {@code POST /tasks/{id}/complete} 端点中
 * 标识完成者并定位后继步骤再聚合所需的代码仓库。
 *
 * @param userId 完成步骤的 worker 用户 id，必须为正数
 * @param repo   目标仓库地址，不能为空
 */
public record CompleteRequest(
    @NotNull @Positive Long userId,
    @NotBlank String repo) {}
