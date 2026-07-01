package com.factory.ai.task.web.dto;

/**
 * 任务完成（complete）请求体。
 *
 * <p>携带 worker 身份与目标仓库地址，用于在 {@code POST /tasks/{id}/complete} 端点中
 * 标识完成者并定位后继步骤再聚合所需的代码仓库。
 *
 * @param userId 完成步骤的 worker 用户 id
 * @param repo   目标仓库地址（用于后继步骤的再聚合上下文）
 */
public record CompleteRequest(Long userId, String repo) {}
