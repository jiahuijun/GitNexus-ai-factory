package com.factory.ai.task.service;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;
import com.factory.ai.task.mapper.TaskStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 任务认领服务，实现 READY→IN_PROGRESS 的原子状态转换。
 *
 * <p>在拆解流水线完成后，所有无依赖（dependsOnCount=0）的任务处于 READY 状态，
 * 等待执行端（人或 AI）认领。本服务通过数据库层面的原子 UPDATE
 * 保证同一任务不会被多个执行端同时认领（乐观并发控制）。</p>
 *
 * @see TaskStepMapper#claimTask(Long, Long, String, String)
 */
@Service
public class TaskClaimService {

    private final TaskStepMapper stepMapper;

    /**
     * 构造服务，注入 TaskStep Mapper。
     *
     * @param stepMapper 任务步骤 Mapper，提供原子认领 SQL
     */
    public TaskClaimService(TaskStepMapper stepMapper) { this.stepMapper = stepMapper; }

    /**
     * 原子认领一个 READY 任务，将其状态转为 IN_PROGRESS 并绑定执行者。
     *
     * <p>通过 {@code stepMapper.claimTask} 执行条件 UPDATE
     * （WHERE status = READY），返回受影响行数：
     * <ul>
     *   <li>1 → 认领成功，回查最新 step 返回</li>
     *   <li>0 → 任务非 READY 或不存在，返回 null</li>
     * </ul>
     * 使用 {@code @Transactional} 保证认领与回查的原子性。</p>
     *
     * @param stepId 要认领的任务步骤 id
     * @param userId 执行者用户 id
     * @return 认领成功返回更新后的 TaskStep；失败（非 READY 或不存在）返回 null
     * @throws NoSuchElementException 若认领成功但回查时步骤不存在（不应发生）
     */
    @Transactional
    public TaskStep claim(Long stepId, Long userId) {
        // 原子 UPDATE：仅当 status=READY 时才更新为 IN_PROGRESS 并绑定 userId
        int affected = stepMapper.claimTask(stepId, userId,
                TaskStepStatus.READY.name(), TaskStepStatus.IN_PROGRESS.name());
        if (affected == 0) return null;  // 认领失败：已被他人抢走或非 READY
        TaskStep step = stepMapper.selectById(stepId);
        if (step == null) throw new NoSuchElementException("Step not found: " + stepId);
        return step;
    }

    /**
     * 回退认领：将 IN_PROGRESS 的步骤回退为 READY，清除认领人。
     *
     * <p>用于执行失败场景（如 detectChanges 未通过），使步骤可被重新认领。
     * CAS 语义保证不会误操作已 DONE 的步骤。</p>
     *
     * @param stepId 要回退的步骤 id
     */
    @Transactional
    public void revertClaim(Long stepId) {
        stepMapper.revertClaim(stepId,
                TaskStepStatus.IN_PROGRESS.name(),
                TaskStepStatus.READY.name());
    }
}
