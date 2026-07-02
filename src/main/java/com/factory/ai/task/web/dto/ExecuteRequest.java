package com.factory.ai.task.web.dto;

/**
 * 任务执行请求体。
 *
 * @param userId worker 的用户 ID
 * @param repo   目标仓库名（用于定位本地仓库路径）
 */
public record ExecuteRequest(Long userId, String repo) {}
