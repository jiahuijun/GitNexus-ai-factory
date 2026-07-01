package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 上下文聚合服务，为每个 TaskStep 拉取最新代码上下文并组装 Prompt。
 *
 * <p>在拆解流水线中处于倒数第二步：依赖派生之后、状态设置之前。
 * 对每个 TaskStep 调用 GitNexus {@code context()} 获取符号源码与调用方，
 * 调用 {@code impact()} 获取影响面，再委托 {@link PromptAssemblyService}
 * 组装最终 Prompt，写回 {@code step.contextSnapshot} 与 {@code step.generatedPrompt}。</p>
 *
 * <p>在任务完成时也会被 {@link TaskCompletionService} 同步调用，
 * 为后继任务重新聚合上下文（因为前驱已改完代码，后继的源码/调用方/影响面可能变化）。</p>
 *
 * <p>降级策略：当 GitNexus 返回 null 时，使用空上下文 / 未知影响面占位，
 * 确保流水线不因单点缺失而中断（但符号/影响信息会标注 "(unavailable)" / "UNKNOWN"）。</p>
 *
 * @see PromptAssemblyService
 * @see TaskDecompositionService
 * @see TaskCompletionService
 */
@Service
public class ContextAggregationService {

    private final GitNexusClient gitNexus;
    private final PromptAssemblyService promptSvc;

    /**
     * 构造服务，注入 GitNexus 客户端与提示词组装服务。
     *
     * @param gitNexus  GitNexus 客户端，用于 context() / impact() 调用
     * @param promptSvc 提示词组装服务，负责将上下文拼成最终 Prompt
     */
    public ContextAggregationService(GitNexusClient gitNexus, PromptAssemblyService promptSvc) {
        this.gitNexus = gitNexus;
        this.promptSvc = promptSvc;
    }

    /**
     * 为单个 TaskStep 聚合代码上下文并组装 Prompt，写回 step 实体。
     *
     * <p>流程：
     * <ol>
     *   <li>调 {@code gitNexus.context()} 拉取符号源码、调用方、行号</li>
     *   <li>调 {@code gitNexus.impact("upstream")} 拉取影响面</li>
     *   <li>若 ctx 非空，用其 filePath 回填 step.targetFile（确保文件定位准确）</li>
     *   <li>调 {@link PromptAssemblyService#assemble} 组装 Prompt，ctx/impact 为 null 时用占位降级</li>
     * </ol>
     * </p>
     *
     * @param step        要聚合上下文的任务步骤；方法会就地修改其 targetFile / contextSnapshot / generatedPrompt
     * @param repo        目标仓库名称，传给 GitNexus 定位代码
     * @param requirement 原始产品需求文本，嵌入 Prompt 的 Task 区
     */
    public void aggregate(TaskStep step, String repo, String requirement) {
        // 拉取符号上下文：源码、调用方、行号范围
        SymbolContext ctx = gitNexus.context(step.getTargetSymbol(), repo);
        // 拉取上游影响面：改这个符号会波及哪些直接依赖
        ImpactResult impact = gitNexus.impact(step.getTargetSymbol(), "upstream", repo);
        // ctx 有值时回填 targetFile，确保后续 Prompt 与文件操作定位准确
        if (ctx != null) {
            step.setTargetFile(ctx.filePath());
        }
        // 生成上下文摘要（供 Web 端预览）与最终 Prompt（供执行端使用）
        step.setContextSnapshot(summarize(ctx));
        // ctx/impact 为 null 时走降级占位，保证流水线不中断
        step.setGeneratedPrompt(promptSvc.assemble(step, ctx != null ? ctx : emptyContext(step),
            impact != null ? impact : emptyImpact(step), requirement));
    }

    /**
     * 将 SymbolContext 摘要为可读文本，存入 {@code step.contextSnapshot} 供 Web 端预览。
     *
     * <p>包含符号名/类型、文件路径、源码（或 truncated 标记）、入边调用方列表。</p>
     *
     * @param ctx 符号上下文；为 null 时返回 "(unavailable)"
     * @return 摘要文本
     */
    private String summarize(SymbolContext ctx) {
        if (ctx == null) return "(unavailable)";
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(ctx.name()).append(" (").append(ctx.kind()).append(")\n");
        sb.append("File: ").append(ctx.filePath()).append("\n\n");
        sb.append("Source:\n").append(ctx.sourceContent() != null ? ctx.sourceContent() : "(truncated)").append("\n\n");
        sb.append("Incoming calls:\n");
        for (var r : ctx.incomingCalls()) sb.append("- ").append(r.name()).append("\n");
        return sb.toString();
    }

    /**
     * 构造降级空上下文：当 GitNexus context() 返回 null 时使用。
     *
     * <p>所有字段以空串/占位填充，确保 {@link PromptAssemblyService#assemble} 不会 NPE。</p>
     *
     * @param step 当前任务步骤，用其 targetSymbol / targetFile 填充占位上下文
     * @return 全部字段为占位值的 SymbolContext
     */
    private SymbolContext emptyContext(TaskStep step) {
        return new SymbolContext("", step.getTargetSymbol(), "", step.getTargetFile(), null, null,
            "(unavailable)", List.of(), List.of());
    }

    /**
     * 构造降级空影响面：当 GitNexus impact() 返回 null 时使用。
     *
     * <p>directDependents 为空 Map，severity 标为 "UNKNOWN"，避免 NPE。</p>
     *
     * @param step 当前任务步骤，用其 targetSymbol 填充占位影响面
     * @return 空影响面 ImpactResult
     */
    private ImpactResult emptyImpact(TaskStep step) {
        return new ImpactResult(step.getTargetSymbol(), "upstream", "UNKNOWN", java.util.Map.of());
    }
}
