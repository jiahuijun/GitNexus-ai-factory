package com.factory.ai.task.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 任务分解（decompose）请求体。
 *
 * <p>携带原始需求文本、目标仓库与管理员 id，用于在 {@code POST /tasks/decompose} 端点
 * 触发任务分解流水线，将高层需求拆解为可执行的步骤序列。
 *
 * @param requirement 需求文本，不能为空
 * @param repo         目标仓库名，不能为空
 * @param adminId      发起分解的管理员 id，必须为正数
 */
public record DecomposeRequest(
    @NotBlank String requirement,
    @NotBlank String repo,
    @NotNull @Positive Long adminId) {}
