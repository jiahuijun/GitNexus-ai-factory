package com.factory.ai.task.web.dto;

/**
 * 任务分解（decompose）请求体。
 *
 * <p>携带原始需求文本、目标仓库与管理员 id，用于在 {@code POST /tasks/decompose} 端点
 * 触发任务分解流水线，将高层需求拆解为可执行的步骤序列。
 *
 * @param requirement 需求文本，作为分解流水线的输入
 * @param repo         目标仓库地址，供 GitNexus 等上游工具分析代码上下文
 * @param adminId      发起分解的管理员 id，用于权限校验与审计
 */
public record DecomposeRequest(String requirement, String repo, Long adminId) {}
