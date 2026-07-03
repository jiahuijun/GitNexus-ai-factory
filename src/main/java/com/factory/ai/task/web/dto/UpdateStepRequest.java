package com.factory.ai.task.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 更新任务步骤请求体。
 *
 * <p>用于 {@code PUT /tasks/steps/{stepId}} 端点，允许用户编辑已生成步骤的以下字段：
 * <ul>
 *   <li>{@code stepName} — 步骤名称</li>
 *   <li>{@code targetSymbol} — 目标代码符号</li>
 *   <li>{@code targetFile} — 目标文件路径</li>
 *   <li>{@code designDetail} — 设计详情</li>
 *   <li>{@code generatedPrompt} — 执行提示词</li>
 *   <li>{@code needsReview} — 是否需人工复核</li>
 * </ul>
 *
 * @param stepName        步骤名称，不可为 null
 * @param targetSymbol    目标代码符号，可为空串
 * @param targetFile      目标文件路径，可为空串
 * @param designDetail    设计详情，可为 null
 * @param generatedPrompt 执行提示词，可为 null
 * @param needsReview     是否需人工复核
 */
public record UpdateStepRequest(
        @NotNull String stepName,
        String targetSymbol,
        String targetFile,
        String designDetail,
        String generatedPrompt,
        boolean needsReview
) {}
