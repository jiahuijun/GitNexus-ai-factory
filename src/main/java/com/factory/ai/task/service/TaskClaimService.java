package com.factory.ai.task.service;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.repository.TaskStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskClaimService {

    private final TaskStepRepository stepRepo;

    public TaskClaimService(TaskStepRepository stepRepo) { this.stepRepo = stepRepo; }

    @Transactional
    public TaskStep claim(Long stepId, Long userId) {
        int affected = stepRepo.claimReadyTask(stepId, userId);
        if (affected == 0) return null;
        return stepRepo.findById(stepId).orElseThrow();
    }
}
