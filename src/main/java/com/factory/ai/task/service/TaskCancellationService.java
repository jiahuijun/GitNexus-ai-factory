package com.factory.ai.task.service;

import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务取消与释放服务。
 *
 * <p>解决两类运维问题：
 * <ul>
 *   <li>{@link #cancelTask} — 取消整个任务：所有未完成步骤 → CANCELLED，任务 → CANCELLED。
 *       取消步骤时递减后继的 dependsOnCount（与 complete 相同的解锁逻辑）。</li>
 *   <li>{@link #releaseStep} — 释放单个卡住的 IN_PROGRESS 步骤 → READY，清除认领人。
 *       用于 Worker 崩溃后恢复。</li>
 * </ul>
 */
@Service
public class TaskCancellationService {

    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final TaskDependencyMapper depMapper;
    private final ContextAggregationService aggregationSvc;

    public TaskCancellationService(TaskMapper taskMapper, TaskStepMapper stepMapper,
            TaskDependencyMapper depMapper, ContextAggregationService aggregationSvc) {
        this.taskMapper = taskMapper; this.stepMapper = stepMapper;
        this.depMapper = depMapper; this.aggregationSvc = aggregationSvc;
    }

    /**
     * 取消整个任务：所有未完成（非 DONE）步骤标记为 CANCELLED，任务标记为 CANCELLED。
     *
     * <p>对每个被取消的步骤，递减其后继步骤的 dependsOnCount（与 complete 的解锁逻辑一致）。
     * 当后继的 dependsOnCount 归零时，重新聚合上下文并设为 READY。</p>
     *
     * @param taskId 任务 ID
     * @throws NoSuchElementException 任务不存在
     */
    @Transactional
    public void cancelTask(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) throw new NoSuchElementException("Task not found: " + taskId);

        List<TaskStep> steps = stepMapper.findByTaskId(taskId);
        for (TaskStep step : steps) {
            if (step.getStatus() == TaskStepStatus.DONE) continue;
            if (step.getStatus() == TaskStepStatus.CANCELLED) continue;

            // 标记步骤为 CANCELLED
            step.setStatus(TaskStepStatus.CANCELLED);
            stepMapper.updateById(step);

            // 递减后继步骤的 dependsOnCount
            List<TaskDependency> successors = depMapper.findByFromStepId(step.getId());
            for (var dep : successors) {
                TaskStep succ = stepMapper.selectById(dep.getToStepId());
                if (succ == null || succ.getStatus() == TaskStepStatus.DONE
                    || succ.getStatus() == TaskStepStatus.CANCELLED) continue;
                succ.decrementDependsOnCount();
                if (succ.getDependsOnCount() == 0 && succ.getStatus() == TaskStepStatus.PENDING) {
                    aggregationSvc.aggregate(succ, "", "");
                    succ.setStatus(TaskStepStatus.READY);
                    succ.setReaggregatedAt(LocalDateTime.now());
                }
                stepMapper.updateById(succ);
            }
        }

        task.setStatus(TaskStatus.CANCELLED);
        taskMapper.updateById(task);
    }

    /**
     * 释放单个卡住的 IN_PROGRESS 步骤，回退为 READY 并清除认领人。
     *
     * <p>用于 Worker 崩溃后恢复：步骤卡在 IN_PROGRESS 无法继续，
     * 释放后可被其他 Worker 重新认领。</p>
     *
     * @param stepId 步骤 ID
     * @return true 表示释放成功；false 表示步骤不存在或非 IN_PROGRESS
     */
    @Transactional
    public boolean releaseStep(Long stepId) {
        TaskStep step = stepMapper.selectById(stepId);
        if (step == null) return false;
        if (step.getStatus() != TaskStepStatus.IN_PROGRESS) return false;

        stepMapper.revertClaim(stepId,
            TaskStepStatus.IN_PROGRESS.name(),
            TaskStepStatus.READY.name());
        return true;
    }
}
