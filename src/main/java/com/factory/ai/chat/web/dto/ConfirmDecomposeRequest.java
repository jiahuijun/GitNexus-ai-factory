package com.factory.ai.chat.web.dto;

import com.factory.ai.task.service.LlmGateway;

import java.util.List;

/**
 * 确认拆解请求体（可携带用户修改后的草稿）。
 *
 * @param drafts 用户在预览中编辑后的任务草稿（null 时后端重新调 LLM 生成）
 */
public record ConfirmDecomposeRequest(List<LlmGateway.TaskDraft> drafts) {}
