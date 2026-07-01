package com.factory.ai.task.repository;

import com.factory.ai.task.domain.TaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * {@link TaskDependency} 步骤依赖关系实体的 Spring Data JPA 仓储。
 *
 * <p>复合主键为 {@link TaskDependency.PK}（{@code fromStepId} + {@code toStepId}）。
 * 主要用于查询某个步骤的后继依赖，以便在前置步骤完成时递减后继步骤的 dependsOnCount。
 */
public interface TaskDependencyRepository extends JpaRepository<TaskDependency, TaskDependency.PK> {

    /**
     * 按前置步骤 ID 查询所有依赖关系，即找出所有"依赖该前置步骤"的后继步骤。
     * <p>典型用法：当 {@code fromStepId} 完成时，遍历返回结果，对每个 {@code toStepId}
     * 调用 {@link com.factory.ai.task.domain.TaskStep#decrementDependsOnCount()}。
     *
     * @param fromStepId 前置步骤 ID
     * @return 以该步骤为前置的所有依赖关系列表
     */
    List<TaskDependency> findByFromStepId(Long fromStepId);
}
