package com.factory.ai.task.repository;

import com.factory.ai.task.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Task} 父任务实体的 Spring Data JPA 仓储。
 *
 * <p>提供标准的 CRUD 操作（来自 {@link JpaRepository}），任务的状态流转与分解逻辑
 * 由上层服务编排，本仓储暂不包含自定义查询。
 */
public interface TaskRepository extends JpaRepository<Task, Long> {
}
