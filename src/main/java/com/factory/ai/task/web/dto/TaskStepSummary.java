package com.factory.ai.task.web.dto;

import com.factory.ai.task.domain.TaskStep;

/**
 * 步骤列表项 DTO（不含 contextSnapshot / generatedPrompt / designDetail 等大字段）。
 *
 * @param id              步骤 ID
 * @param taskId          所属任务 ID
 * @param stepName        步骤名称
 * @param targetSymbol    目标符号
 * @param targetFile      目标文件路径
 * @param status          步骤状态
 * @param assigneeId      认领人 ID
 * @param dependsOnCount  未完成前置步骤数
 * @param needsReview     是否需要人工复核
 */
public record TaskStepSummary(Long id, Long taskId, String stepName, String targetSymbol,
    String targetFile, String status, Long assigneeId, int dependsOnCount,
    boolean needsReview) {
    public static TaskStepSummary from(TaskStep s) {
        return new TaskStepSummary(s.getId(), s.getTaskId(), s.getStepName(),
            s.getTargetSymbol(), s.getTargetFile(), s.getStatus().name(),
            s.getAssigneeId(), s.getDependsOnCount(), s.isNeedsReview());
    }
}
