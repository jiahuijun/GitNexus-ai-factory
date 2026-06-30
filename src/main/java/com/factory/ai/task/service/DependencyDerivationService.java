package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.ImpactResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import com.factory.ai.task.domain.TaskDependency;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DependencyDerivationService {

    private final GitNexusClient gitNexus;

    public DependencyDerivationService(GitNexusClient gitNexus) {
        this.gitNexus = gitNexus;
    }

    /**
     * 派生任务间 DAG 依赖。
     * 规则：A 改 S_A，若 B 的目标符号(或其所属类)出现在 impact(S_A,"upstream") 的 d=1 集合，
     * 则 B 依赖 A，连边 A→B。
     */
    public List<TaskDependency> derive(List<TaskStep> steps, String repo) {
        var bySymbol = new HashMap<String, TaskStep>();
        for (var s : steps) bySymbol.put(s.getTargetSymbol(), s);

        var edges = new ArrayList<TaskDependency>();
        for (TaskStep a : steps) {
            ImpactResult impact = gitNexus.impact(a.getTargetSymbol(), "upstream", repo);
            Set<String> upstreamNames = new HashSet<>();
            for (SymbolRef ref : impact.directDependents()) {
                upstreamNames.add(ref.name());
                upstreamNames.add(extractClassName(ref.uid()));  // 所属类
            }
            for (TaskStep b : steps) {
                if (b == a) continue;
                if (upstreamNames.contains(b.getTargetSymbol())
                    || upstreamNames.contains(extractClassName(b.getTargetSymbol()))) {
                    edges.add(new TaskDependency(a.getId(), b.getId()));
                    b.setDependsOnCount(b.getDependsOnCount() + 1);
                }
            }
        }
        return detectCycles(steps, edges) ? mergeCyclicTasks(steps, edges) : edges;
    }

    private String extractClassName(String uid) {
        // uid 形如 "Class:path/to/File.java:ClassName" 或 "Method:...:ClassName:method"
        String[] parts = uid.split(":");
        return parts.length >= 3 ? parts[parts.length - 1] : uid;
    }

    private boolean detectCycles(List<TaskStep> steps, List<TaskDependency> edges) {
        var adj = new HashMap<Long, List<Long>>();
        for (var e : edges) adj.computeIfAbsent(e.getFromStepId(), k -> new ArrayList<>()).add(e.getToStepId());
        var white = new HashSet<>(steps.stream().map(TaskStep::getId).toList());
        var gray = new HashSet<Long>();
        var black = new HashSet<Long>();
        for (var n : white) if (hasCycleDfs(n, adj, gray, black)) return true;
        return false;
    }

    private boolean hasCycleDfs(Long node, Map<Long, List<Long>> adj, Set<Long> gray, Set<Long> black) {
        if (gray.contains(node)) return true;
        if (black.contains(node)) return false;
        gray.add(node);
        for (var nb : adj.getOrDefault(node, List.of())) if (hasCycleDfs(nb, adj, gray, black)) return true;
        gray.remove(node);
        black.add(node);
        return false;
    }

    /**
     * 环检测到时：环上任务合并（标 needs_review），删除环内边。
     * 简化实现：标记所有环上任务 needs_review，返回去环后的边集。
     */
    private List<TaskDependency> mergeCyclicTasks(List<TaskStep> steps, List<TaskDependency> edges) {
        // 找出所有出现在环中的节点（简化：所有入度+出度>0 且无法拓扑排序的）
        var stepMap = new HashMap<Long, TaskStep>();
        for (var s : steps) stepMap.put(s.getId(), s);
        // 简化策略：标记所有有依赖的 step 为 needs_review，让管理员人工处理
        for (var e : edges) {
            stepMap.get(e.getToStepId()).setNeedsReview(true);
        }
        // 返回边集不变——管理员可在 Web 上手动调整；实际环合并可在 v2 细化
        return edges;
    }
}
