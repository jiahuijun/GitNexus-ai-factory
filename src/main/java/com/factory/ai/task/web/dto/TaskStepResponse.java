package com.factory.ai.task.web.dto;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;

/**
 * 任务步骤响应体，用于 {@code POST /tasks/{id}/claim} 成功后返回给 worker。
 *
 * <p>携带 worker 执行该步骤所需的关键信息：所属任务、步骤名、目标符号、当前状态，
 * 以及由 LLM 生成的执行指令（prompt）。
 *
 * @param taskId       所属任务 id
 * @param stepName     步骤名称，标识步骤在流水线中的角色
 * @param targetSymbol 目标代码符号（如函数/类名），供 worker 定位操作对象
 * @param status       步骤当前状态（如 CLAIMED）
 * @param instruction  LLM 生成的执行指令（prompt），指导 worker 如何完成该步骤
 */
public record TaskStepResponse(Long taskId, String stepName, String targetSymbol,
                               TaskStepStatus status, String instruction) {

    /**
     * 将领域实体 {@link TaskStep} 转换为对外响应 DTO。
     *
     * <p>该映射方法将领域对象中的字段一一映射到 DTO，其中 {@code instruction}
     * 取自 {@link TaskStep#getGeneratedPrompt()}（领域层字段名与对外字段名不同，
     * 对外暴露为 instruction 以贴合 worker 视角）。
     *
     * @param s 领域层的任务步骤实体
     * @return 转换后的响应 DTO
     */
    public static TaskStepResponse from(TaskStep s) {
        // generatedPrompt（领域层）映射为 instruction（对外响应），语义保持一致
        return new TaskStepResponse(s.getTaskId(), s.getStepName(), s.getTargetSymbol(),
            s.getStatus(), s.getGeneratedPrompt());
    }
}
