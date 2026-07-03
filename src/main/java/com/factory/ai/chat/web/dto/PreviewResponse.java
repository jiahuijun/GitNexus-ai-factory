package com.factory.ai.chat.web.dto;

import com.factory.ai.task.service.LlmGateway;

import java.util.List;

/**
 * 拆解预览响应体（不入库，仅展示给用户确认）。
 *
 * @param drafts 任务草稿列表
 */
public record PreviewResponse(List<LlmGateway.TaskDraft> drafts) {}
