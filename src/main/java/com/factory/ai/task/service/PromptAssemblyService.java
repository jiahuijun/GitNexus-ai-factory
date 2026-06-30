package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;

@Service
public class PromptAssemblyService {

    public String assemble(TaskStep step, SymbolContext ctx, ImpactResult impact, String requirement) {
        String callers = ctx.incomingCalls().stream()
            .map(r -> "- " + r.name() + " (" + r.filePath() + ")")
            .reduce("", (a, b) -> a + b + "\n");
        String blast = impact.directDependents().stream()
            .map(r -> "- " + r.name() + " (" + r.filePath() + ")")
            .reduce("", (a, b) -> a + b + "\n");
        String fileLoc = ctx.filePath() != null ? ctx.filePath() : step.getTargetFile();
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
