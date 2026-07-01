package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import java.util.List;

/**
 * LLM 网关接口，负责调用大模型将产品需求拆解为任务草稿列表。
 *
 * <p>在 AI Factory 拆解流水线中处于第二步：GitNexus {@code query()} 摸底之后、
 * {@link DependencyDerivationService} 派生依赖之前。实现类通过 Spring AI
 * 调用大模型（如 {@link SpringAiLlmGateway}），将需求 + 摸底结果作为输入，
 * 输出结构化的 {@link TaskDraft} 列表。</p>
 *
 * <p>"不降级"原则：任何 LLM 调用失败均抛出 {@link LlmException}（非受检），
 * 触发事务回滚，避免落库半成品草稿。</p>
 *
 * @see SpringAiLlmGateway
 * @see LlmPromptBuilder
 */
public interface LlmGateway {

    /**
     * 基于产品需求 + GitNexus query 摸底结果，调用 LLM 输出任务草稿列表。
     *
     * <p>草稿中的 {@code targetSymbol} 必须来自 {@code context} 的符号列表，
     * 不得凭空发明（由 {@link LlmPromptBuilder#SYSTEM_PROMPT} 约束）。
     * 当摸底结果为空时，返回空列表，调用方据此将父任务标记为 DECOMPOSING_FAILED。</p>
     *
     * @param requirement 管理员提交的产品需求（自然语言），作为 LLM 拆解的主输入
     * @param context     GitNexus {@code query()} 返回的摸底结果，包含相关符号列表与执行流名称；
     *                    LLM 必须从中选取 {@code targetSymbol}
     * @return 任务草稿列表；空列表表示摸底无相关符号或 LLM 判定无需拆解
     * @throws LlmException 当 LLM 调用、反序列化或网络出错时抛出（不降级）
     */
    List<TaskDraft> splitTasks(String requirement, QueryResult context);

    /**
     * 任务草稿记录，由 LLM 输出的单条拆解结果。
     *
     * @param stepName      动词短语，描述该任务做什么（如 "加getVipLevel方法"）
     * @param targetSymbol  真实符号名，必须来自摸底结果；用于后续 GitNexus context()/impact() 调用
     * @param instruction   给执行员工的简明指令
     */
    record TaskDraft(String stepName, String targetSymbol, String instruction) {}
}
