package com.factory.ai.task.web.dto;

/**
 * 依赖关系响应 DTO，表示 DAG 中的一条边 fromStepId → toStepId。
 *
 * @param fromStepId   前置步骤 ID
 * @param toStepId     后继步骤 ID
 * @param fromStepName 前置步骤名称（便于阅读）
 * @param toStepName   后继步骤名称
 */
public record TaskDependencyResponse(Long fromStepId, Long toStepId,
    String fromStepName, String toStepName) {
}
