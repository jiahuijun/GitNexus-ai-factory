package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.ImpactResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import com.factory.ai.task.domain.TaskDependency;
import com.factory.ai.task.domain.TaskStep;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 依赖派生服务，基于 GitNexus impact() 数据推导任务间的 DAG 依赖。
 *
 * <p>在拆解流水线中位于 LLM 拆解、步骤入库之后，上下文聚合之前。
 * 核心职责：判断"改 A 符号会不会影响 B 符号"，若是则连边 A→B（B 依赖 A 先完成）。</p>
 *
 * <p><b>派生算法</b>：对每个任务 A，调 {@code gitNexus.impact(S_A, "upstream")} 获取其
 * 直接上游依赖集合（d=1），将其中每个 SymbolRef 的 name 与所属类名（由 uid 解析得到）
 * 加入候选集；再遍历其他任务 B，若 B 的 targetSymbol 或其所属类出现在候选集中，
 * 则连边 A→B 并递增 B 的 dependsOnCount。</p>
 *
 * <p><b>环检测</b>：连边后用 DFS 三色标记法（白/灰/黑）检测是否有环。
 * 若存在环，调用 {@link #mergeCyclicTasks} 标记相关任务 needs_review 让管理员人工处理
 * （环合并策略 v1 简化：标 needs_review，返回原边集；v2 再细化自动合并）。</p>
 *
 * @see TaskDecompositionService
 */
@Service
public class DependencyDerivationService {

    private final GitNexusClient gitNexus;

    /**
     * 构造服务，注入 GitNexus 客户端。
     *
     * @param gitNexus GitNexus 客户端，用于 impact() 调用获取符号影响面
     */
    public DependencyDerivationService(GitNexusClient gitNexus) {
        this.gitNexus = gitNexus;
    }

    /**
     * 派生任务间 DAG 依赖。
     *
     * <p>规则：A 改 S_A，若 B 的目标符号(或其所属类)出现在 impact(S_A,"upstream") 的 d=1 集合，
     * 则 B 依赖 A，连边 A→B。</p>
     *
     * <p>步骤：
     * <ol>
     *   <li>建立 symbol→step 索引</li>
     *   <li>对每个 A：拉 impact，收集上游 name + uid 解析出的类名</li>
     *   <li>对每个其他 B：B 的 symbol 或类名命中候选集 → 连边 A→B，B.dependsOnCount++</li>
     *   <li>环检测：有环则 mergeCyclicTasks（标 needs_review），无环则直接返回边集</li>
     * </ol>
     * </p>
     *
     * @param steps 已入库的任务步骤列表（含 id 与 targetSymbol）；方法会就地修改 dependsOnCount / needsReview
     * @param repo  目标仓库名称，传给 GitNexus
     * @return 派生出的依赖边列表（TaskDependency）
     */
    public List<TaskDependency> derive(List<TaskStep> steps, String repo) {
        // 建立 symbol → step 索引，便于后续按符号名查找对应步骤
        var bySymbol = new HashMap<String, TaskStep>();
        for (var s : steps) bySymbol.put(s.getTargetSymbol(), s);

        var edges = new ArrayList<TaskDependency>();
        for (TaskStep a : steps) {
            // 拉 A 的上游影响面：改 S_A 会被谁直接依赖
            ImpactResult impact = gitNexus.impact(a.getTargetSymbol(), "upstream", repo);
            // 候选集：收集 impact 里每个 ref 的 name + 从 uid 解析出的所属类名
            Set<String> upstreamNames = new HashSet<>();
            for (SymbolRef ref : impact.directDependents()) {
                upstreamNames.add(ref.name());
                upstreamNames.add(extractClassName(ref.uid()));  // 所属类，用于匹配类级符号
            }
            // 遍历其他任务 B，命中候选集则连边 A→B
            for (TaskStep b : steps) {
                if (b == a) continue;
                // B 的 symbol 直接命中，或 B 的所属类命中（跨方法同类的依赖）
                if (upstreamNames.contains(b.getTargetSymbol())
                    || upstreamNames.contains(extractClassName(b.getTargetSymbol()))) {
                    edges.add(new TaskDependency(a.getId(), b.getId()));
                    b.setDependsOnCount(b.getDependsOnCount() + 1);
                }
            }
        }
        // 环检测：有环走合并策略，无环直接返回
        return detectCycles(steps, edges) ? mergeCyclicTasks(steps, edges) : edges;
    }

    /**
     * 从 GitNexus uid 中解析所属类名。
     *
     * <p>uid 格式形如：
     * <ul>
     *   <li>类级：{@code "Class:path/to/File.java:ClassName"}</li>
     *   <li>方法级：{@code "Method:...:ClassName:method"}</li>
     * </ul>
     * 取冒号分隔后最后一段作为类名；解析失败时原样返回 uid。</p>
     *
     * @param uid GitNexus SymbolRef 的 uid 字符串
     * @return 解析出的类名；无法解析时返回原 uid
     */
    private String extractClassName(String uid) {
        // uid 形如 "Class:path/to/File.java:ClassName" 或 "Method:...:ClassName:method"
        String[] parts = uid.split(":");
        return parts.length >= 3 ? parts[parts.length - 1] : uid;
    }

    /**
     * 使用 DFS 三色标记法（白/灰/黑）检测任务依赖图中是否存在环。
     *
     * <p>三色语义：
     * <ul>
     *   <li>白色：未访问</li>
     *   <li>灰色：正在访问（在当前 DFS 栈中）</li>
     *   <li>黑色：已完成访问（子树已全部遍历）</li>
     * </ul>
     * DFS 过程中遇到灰色节点 → 存在环（回边）。</p>
     *
     * @param steps 所有步骤（提供节点 id 集合）
     * @param edges 依赖边列表
     * @return true 表示存在环；false 表示无环（合法 DAG）
     */
    private boolean detectCycles(List<TaskStep> steps, List<TaskDependency> edges) {
        // 构建邻接表
        var adj = new HashMap<Long, List<Long>>();
        for (var e : edges) adj.computeIfAbsent(e.getFromStepId(), k -> new ArrayList<>()).add(e.getToStepId());
        // 初始全部为白色
        var white = new HashSet<>(steps.stream().map(TaskStep::getId).toList());
        var gray = new HashSet<Long>();
        var black = new HashSet<Long>();
        // 对每个白色节点启动 DFS
        for (var n : white) if (hasCycleDfs(n, adj, gray, black)) return true;
        return false;
    }

    /**
     * DFS 递归检测环（三色标记法核心）。
     *
     * @param node 当前访问节点 id
     * @param adj  邻接表
     * @param gray 灰色集合（当前 DFS 栈中的节点）
     * @param black 黑色集合（已完成访问的节点）
     * @return true 表示发现回边（环）；false 表示子树无环
     */
    private boolean hasCycleDfs(Long node, Map<Long, List<Long>> adj, Set<Long> gray, Set<Long> black) {
        if (gray.contains(node)) return true;  // 遇到灰色 = 回边 = 有环
        if (black.contains(node)) return false; // 已完成，无需重复访问
        gray.add(node);                         // 标记为正在访问
        for (var nb : adj.getOrDefault(node, List.of())) if (hasCycleDfs(nb, adj, gray, black)) return true;
        gray.remove(node);                      // 回溯：移出灰色栈
        black.add(node);                        // 标记为已完成
        return false;
    }

    /**
     * 环检测到时的合并策略：标记环上任务 needs_review，让管理员人工处理。
     *
     * <p>环检测到时：环上任务合并（标 needs_review），删除环内边。
     * 简化实现：标记所有环上任务 needs_review，返回去环后的边集。</p>
     *
     * <p>v1 简化策略：标记所有被依赖的 step（toStepId）为 needs_review，
     * 边集不变——管理员可在 Web 上手动调整。真正的自动环合并（合并为一个复合任务）
     * 留待 v2 细化。</p>
     *
     * @param steps 所有任务步骤
     * @param edges 依赖边列表
     * @return 边集（v1 不变，交由管理员调整）
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
