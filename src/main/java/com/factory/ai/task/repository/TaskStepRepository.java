package com.factory.ai.task.repository;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

/**
 * {@link TaskStep} 步骤实体的 Spring Data JPA 仓储。
 *
 * <p>除标准 CRUD 外，提供原子认领（claim）查询，用于在多用户/多 AI 实例并发场景下
 * 安全地将就绪步骤（READY）转入执行中（IN_PROGRESS）。
 */
public interface TaskStepRepository extends JpaRepository<TaskStep, Long> {

    /**
     * 原子认领步骤：通过单条 UPDATE...WHERE 语句在数据库层面完成状态切换与认领人赋值，
     * 避免先查后改的竞态条件（race condition）。
     *
     * <p>查询逻辑：
     * <pre>
     *   UPDATE task_step
     *   SET status = :newStatus, assignee_id = :userId
     *   WHERE id = :taskId AND status = :expectedStatus
     * </pre>
     * 只有当前状态仍为 {@code expectedStatus} 时才会更新成功，返回受影响行数（1 表示认领成功，
     * 0 表示已被他人抢走或状态已变）。这种"比较并交换"（CAS）式写法天然防止并发重复认领。
     *
     * <p>{@link Modifying} 注解的 {@code clearAutomatically=true} 会在执行后清空持久化上下文，
     * 确保后续读取拿到最新数据；{@code flushAutomatically=true} 在执行前刷入待写更改，
     * 保证查询基于最新状态。
     *
     * @param taskId         要认领的步骤 ID
     * @param userId         认领人 ID
     * @param expectedStatus 期望的当前状态（通常为 READY）
     * @param newStatus      认领后切换到的目标状态（通常为 IN_PROGRESS）
     * @return 受影响行数，1 表示认领成功，0 表示竞争失败
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskStep s SET s.status = :newStatus, s.assigneeId = :userId " +
           "WHERE s.id = :taskId AND s.status = :expectedStatus")
    int claimTask(Long taskId, Long userId,
                  TaskStepStatus expectedStatus, TaskStepStatus newStatus);

    /**
     * 认领就绪步骤的便捷方法：将状态从 {@link TaskStepStatus#READY} 原子切换为
     * {@link TaskStepStatus#IN_PROGRESS} 并设置认领人。
     *
     * @param taskId 要认领的步骤 ID
     * @param userId 认领人 ID
     * @return 1 表示认领成功，0 表示竞争失败
     */
    default int claimReadyTask(Long taskId, Long userId) {
        return claimTask(taskId, userId, TaskStepStatus.READY, TaskStepStatus.IN_PROGRESS);
    }

    /**
     * 按父任务 ID 与状态查询步骤列表。
     *
     * @param taskId  父任务 ID
     * @param status  目标状态
     * @return 匹配的步骤列表
     */
    List<TaskStep> findByTaskIdAndStatus(Long taskId, TaskStepStatus status);
}
