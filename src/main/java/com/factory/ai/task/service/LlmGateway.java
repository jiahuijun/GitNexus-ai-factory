package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.chat.session.ChatMessage;

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
     * 调用 LLM 执行步骤提示词，返回生成的完整文件内容。
     *
     * <p>在 AI Worker 执行流程中使用：将 {@link TaskStep#getGeneratedPrompt()}
     * 发送给 LLM，LLM 基于其中的目标符号、当前源码、调用方、设计详情，
     * 输出修改后的完整文件内容。Worker 将其写入目标文件后调用 complete。</p>
     *
     * @param prompt 步骤的 generated_prompt，含完整上下文与设计指令
     * @return LLM 生成的完整文件内容（纯文本，不含 markdown 围栏）
     * @throws LlmException 当 LLM 调用或网络出错时抛出（不降级）
     */
    String executeStep(String prompt);

    /**
     * 澄清模式：基于需求 + GitNexus 摸底结果 + 对话历史，输出下一个问题或标记完成。
     *
     * <p>在对话式需求澄清流程中使用。每轮调用返回 LLM 的下一个澄清问题，
     * 或当信息已充分时标记 {@code ready=true} 并合成精炼需求段落。</p>
     *
     * @param requirement 原始需求文本
     * @param context     GitNexus query() 返回的摸底结果（在会话开始时调用一次后缓存）
     * @param history     对话历史（user/assistant 交替的 ChatMessage 列表）
     * @return ClarifyReply：message=回复文本，ready=是否已澄清，refinedRequirement=ready 时的精炼需求
     * @throws LlmException 当 LLM 调用或网络出错时抛出（不降级）
     */
    ClarifyReply clarify(String requirement, QueryResult context, List<ChatMessage> history);

    /**
     * 任务草稿记录，由 LLM 输出的单条拆解结果。
     *
     * @param stepName      动词短语，描述该任务做什么（如 "加getVipLevel方法"）
     * @param targetSymbol  真实符号名（类名/函数名/方法名，不含文件名），必须来自摸底结果；
     *                      用于后续 GitNexus context()/impact() 调用
     * @param designDetail  详细设计方案，包含具体的产出物类名、方法名、方法签名、
     *                      实现思路（伪代码或关键步骤）、依赖的其他模块
     */
    record TaskDraft(String stepName, String targetSymbol, String designDetail) {}

    /**
     * 澄清回复记录。
     *
     * @param message           LLM 的回复文本（澄清问题或"需求已澄清"提示）
     * @param ready             是否已收集足够信息可以拆解
     * @param refinedRequirement ready=true 时合成的精炼需求段落；null 表示尚未完成澄清
     */
    record ClarifyReply(String message, boolean ready, String refinedRequirement) {}
}
