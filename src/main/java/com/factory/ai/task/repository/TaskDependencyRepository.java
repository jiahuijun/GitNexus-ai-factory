package com.factory.ai.task.repository;

import com.factory.ai.task.domain.TaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, TaskDependency.PK> {
    List<TaskDependency> findByFromStepId(Long fromStepId);
}
