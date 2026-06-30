package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ContextAggregationService {

    private final GitNexusClient gitNexus;
    private final PromptAssemblyService promptSvc;

    public ContextAggregationService(GitNexusClient gitNexus, PromptAssemblyService promptSvc) {
        this.gitNexus = gitNexus;
        this.promptSvc = promptSvc;
    }

    public void aggregate(TaskStep step, String repo, String requirement) {
        SymbolContext ctx = gitNexus.context(step.getTargetSymbol(), repo);
        ImpactResult impact = gitNexus.impact(step.getTargetSymbol(), "upstream", repo);
        if (ctx != null) {
            step.setTargetFile(ctx.filePath());
        }
        step.setContextSnapshot(summarize(ctx));
        step.setGeneratedPrompt(promptSvc.assemble(step, ctx != null ? ctx : emptyContext(step),
            impact != null ? impact : emptyImpact(step), requirement));
    }

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

    private SymbolContext emptyContext(TaskStep step) {
        return new SymbolContext("", step.getTargetSymbol(), "", step.getTargetFile(), null, null,
            "(unavailable)", List.of(), List.of());
    }

    private ImpactResult emptyImpact(TaskStep step) {
        return new ImpactResult(step.getTargetSymbol(), "upstream", "UNKNOWN", java.util.Map.of());
    }
}
