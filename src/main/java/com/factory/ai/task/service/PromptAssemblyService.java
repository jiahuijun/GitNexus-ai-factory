package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;

/**
 * 提示词组装服务，为每个 TaskStep 拼装最终发给执行端（人/AI）的 Prompt。
 *
 * <p>在拆解流水线中由 {@link ContextAggregationService} 调用，
 * 接收 TaskStep 元信息 + GitNexus 拉取的 {@link SymbolContext}（符号源码、调用方）
 * + {@link ImpactResult}（影响面），组装成一段结构化的 Markdown Prompt。</p>
 *
 * <p>Prompt 模板包含以下分区（每个分区都有明确目的）：
 * <ul>
 *   <li><b>Task</b>：步骤名 + 原始需求，让执行端明确目标</li>
 *   <li><b>Target Symbol</b>：符号名 + 文件路径 + 行号范围，精确定位修改点</li>
 *   <li><b>Current Source</b>：GitNexus 拉取的最新源码，避免基于过快照修改</li>
 *   <li><b>Callers</b>：谁调用了该符号——改动必须兼容这些调用方</li>
 *   <li><b>Blast Radius</b>：改该符号会影响谁——风险提示</li>
 *   <li><b>Instruction</b>：执行指令，含 {@code complete_task(id)} 提交步骤</li>
 *   <li><b>Constraints</b>：硬约束，不得改未列入文件、不得破坏调用方契约</li>
 * </ul>
 * </p>
 *
 * @see ContextAggregationService
 */
@Service
public class PromptAssemblyService {

    /**
     * 为单个 TaskStep 组装最终 Prompt。
     *
     * <p>将符号上下文与影响面格式化后嵌入模板。
     * Callers / Blast Radius 为空时以 "(none)" 占位，
     * 源码缺失时以 "(unavailable)" 占位。</p>
     *
     * @param step        当前任务步骤实体，提供 stepName、targetSymbol、targetFile、id
     * @param ctx         GitNexus {@code context()} 返回的符号上下文（源码、调用方、行号）；可为 null
     * @param impact      GitNexus {@code impact()} 返回的影响面结果；可为 null
     * @param requirement 原始产品需求文本，嵌入 Task 区让执行端理解整体目标
     * @return 组装好的 Markdown Prompt 字符串，存入 {@code step.generatedPrompt}
     */
    public String assemble(TaskStep step, SymbolContext ctx, ImpactResult impact, String requirement) {
        // 调用方列表：格式化为 "- name (filePath)" 多行
        String callers = ctx.incomingCalls().stream()
            .map(r -> "- " + r.name() + " (" + r.filePath() + ")")
            .reduce("", (a, b) -> a + b + "\n");
        // 影响面列表：同上格式
        String blast = impact.directDependents().stream()
            .map(r -> "- " + r.name() + " (" + r.filePath() + ")")
            .reduce("", (a, b) -> a + b + "\n");
        // 文件定位：优先用 ctx 的 filePath，回退到 step 上记录的 targetFile
        String fileLoc = ctx.filePath() != null ? ctx.filePath() : step.getTargetFile();
        // 行号范围：起止行都有时才拼 ":start-end"
        String lineRange = (ctx.startLine() != null && ctx.endLine() != null)
            ? ":" + ctx.startLine() + "-" + ctx.endLine() : "";

        return """
            # Task
            %s — 需求: %s

            # Target Symbol
            符号: %s
            文件: %s%s

            # Current Source (fresh)
            %s

            # Callers (谁会调用你改的符号 — 改动要兼容这些调用方)
            %s
            # Blast Radius (改这个符号会影响谁 — 风险提示)
            %s
            # Instruction
            在上述文件中实现需求。直接修改文件，不要只输出代码片段。
            遵循现有代码风格。完成后跑相关测试。
            最后调用 complete_task(%d) 提交。

            # Constraints
            - 不要改未列入 Target 的文件
            - 不要破坏 Callers 中列出的调用方契约
            """.formatted(
                step.getStepName(), requirement,
                step.getTargetSymbol(), fileLoc, lineRange,
                ctx.sourceContent() != null ? ctx.sourceContent() : "(unavailable)",
                callers.isBlank() ? "(none)" : callers,
                blast.isBlank() ? "(none)" : blast,
                step.getId()
            );
    }
}
