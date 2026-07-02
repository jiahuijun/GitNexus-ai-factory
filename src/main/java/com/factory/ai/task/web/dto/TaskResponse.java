package com.factory.ai.task.web.dto;

import com.factory.ai.task.domain.Task;

/**
 * 任务列表 / 详情响应 DTO。
 *
 * @param id         任务 ID
 * @param requirement 原始需求文本
 * @param status     任务状态
 * @param createdBy  创建人 ID
 */
public record TaskResponse(Long id, String requirement, String status, Long createdBy) {
    public static TaskResponse from(Task t) {
        return new TaskResponse(t.getId(), t.getRequirement(), t.getStatus().name(), t.getCreatedBy());
    }
}
