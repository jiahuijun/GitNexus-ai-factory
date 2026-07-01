package com.factory.ai.task.web.dto;

/**
 * 任务领取（claim）请求体。
 *
 * <p>携带 worker 身份信息，用于在 {@code POST /tasks/{id}/claim} 端点中标识
 * 哪个 worker 占用了下一个待执行步骤。
 *
 * @param userId 执行领取操作的 worker 用户 id
 */
public record ClaimRequest(Long userId) {}
