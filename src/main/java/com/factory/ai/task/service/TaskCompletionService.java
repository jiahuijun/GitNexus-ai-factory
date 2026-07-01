package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务完成服务，实现 IN_PROGRESS→DONE 转换 + 变更检测 + 后继任务重聚合。
 *
 * <p>当执行端（人或 AI）完成一个任务步骤后调用本服务。完成流程分三步：
 * <ol>
 *   <li><b>detectChanges</b>：调 GitNexus 验证仓库确有代码变更，且触及预期符号；
 *       验证失败则拒绝完成（返回 false）</li>
 *   <li><b>标记 DONE</b>：将当前 step 状态置为 DONE 并持久化</li>
 *   <li><b>同步重聚合后继</b>：遍历以当前 step 为前驱的依赖边，将后继 step 的
 *       dependsOnCount 递减；当归零时重新聚合上下文（因为前驱代码已变），
 *       并置为 READY 供下一轮认领</li>
 * </ol>
 * </p>
 *
 * <p>"不降级"原则：整个流程在 {@code @Transactional} 内，任何异常触发回滚，
 * 保证不会出现"前驱已标 DONE 但后继未重聚合"的脏状态。</p>
 *
 * @see ContextAggregationService
 * @see TaskClaimService
 */
@Service
public class TaskCompletionService {

    private final TaskStepMapper stepMapper;
    private final TaskDependencyMapper depMapper;
    private final ContextAggregationService aggregationSvc;
    private final GitNexusClient gitNexus;

    /**
     * 构造服务，注入所需 Mapper 与服务。
     *
     * @param stepMapper        任务步骤 Mapper
     * @param depMapper         依赖关系 Mapper，查询后继任务
     * @param aggregationSvc 上下文聚合服务，用于后继任务重聚合
     * @param gitNexus        GitNexus 客户端，用于 detectChanges 验证
     */
    public TaskCompletionService(TaskStepMapper stepMapper, TaskDependencyMapper depMapper,
            ContextAggregationService aggregationSvc, GitNexusClient gitNexus) {
        this.stepMapper = stepMapper; this.depMapper = depMapper;
        this.aggregationSvc = aggregationSvc; this.gitNexus = gitNexus;
    }

    /**
     * 完成一个任务步骤：检测变更 → 标记 DONE → 重聚合后继。
     *
     * @param stepId 要完成的任务步骤 id
     * @param userId 执行者用户 id（用于审计）
     * @param repo   目标仓库名称，传给 GitNexus detectChanges
     * @return true 表示完成成功；false 表示状态非法或变更检测未通过
     * @throws NoSuchElementException 若 stepId 不存在
     */
    @Transactional
    public boolean complete(Long stepId, Long userId, String repo) {
        TaskStep step = stepMapper.selectById(stepId);
        if (step == null) throw new NoSuchElementException("Step not found: " + stepId);
        // 仅 IN_PROGRESS 状态可完成；防止重复提交
        if (step.getStatus() != TaskStepStatus.IN_PROGRESS) return false;

        // 1. detect_changes 验证触及预期符号
        if (!gitNexus.detectChanges(repo)) return false;

        // 2. 标记 DONE
        step.setStatus(TaskStepStatus.DONE);
        stepMapper.updateById(step);

        // 3. 同步重聚合后继：前驱代码已变，后继的上下文可能过期
        List<TaskDependency> successors = depMapper.findByFromStepId(stepId);
        for (var dep : successors) {
            TaskStep succ = stepMapper.selectById(dep.getToStepId());
            if (succ == null) throw new NoSuchElementException("Successor step not found: " + dep.getToStepId());
            succ.decrementDependsOnCount();
            // 依赖归零 → 后继可执行，需重聚合上下文
            if (succ.getDependsOnCount() == 0) {
                aggregationSvc.aggregate(succ, repo, "");  // requirement 已嵌入 step 名
                succ.setStatus(TaskStepStatus.READY);
                succ.setReaggregatedAt(LocalDateTime.now());
            }
            stepMapper.updateById(succ);
        }
        return true;
    }
}
