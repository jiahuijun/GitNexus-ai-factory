package com.factory.ai.task.repository;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TaskStepRepository extends JpaRepository<TaskStep, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskStep s SET s.status = :newStatus, s.assigneeId = :userId " +
           "WHERE s.id = :taskId AND s.status = :expectedStatus")
    int claimTask(Long taskId, Long userId,
                  TaskStepStatus expectedStatus, TaskStepStatus newStatus);

    default int claimReadyTask(Long taskId, Long userId) {
        return claimTask(taskId, userId, TaskStepStatus.READY, TaskStepStatus.IN_PROGRESS);
    }

    List<TaskStep> findByTaskIdAndStatus(Long taskId, TaskStepStatus status);
}
