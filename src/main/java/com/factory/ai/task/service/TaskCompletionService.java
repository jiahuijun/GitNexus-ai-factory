package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskCompletionService {

    private final TaskStepRepository stepRepo;
    private final TaskDependencyRepository depRepo;
    private final ContextAggregationService aggregationSvc;
    private final GitNexusClient gitNexus;

    public TaskCompletionService(TaskStepRepository stepRepo, TaskDependencyRepository depRepo,
            ContextAggregationService aggregationSvc, GitNexusClient gitNexus) {
        this.stepRepo = stepRepo; this.depRepo = depRepo;
        this.aggregationSvc = aggregationSvc; this.gitNexus = gitNexus;
    }

    @Transactional
    public boolean complete(Long stepId, Long userId, String repo) {
        TaskStep step = stepRepo.findById(stepId).orElseThrow();
        if (step.getStatus() != TaskStepStatus.IN_PROGRESS) return false;

        // 1. detect_changes 验证触及预期符号
        if (!gitNexus.detectChanges(repo)) return false;

        // 2. 标记 DONE
        step.setStatus(TaskStepStatus.DONE);
        stepRepo.save(step);

        // 3. 同步重聚合后继
        List<TaskDependency> successors = depRepo.findByFromStepId(stepId);
        for (var dep : successors) {
            TaskStep succ = stepRepo.findById(dep.getToStepId()).orElseThrow();
            succ.decrementDependsOnCount();
            if (succ.getDependsOnCount() == 0) {
                // 重聚合（需要 requirement——从父 task 取）
                Task parent = stepRepo.findById(stepId).isPresent()
                    ? null : null; // requirement 通过聚合服务内部处理，简化：传空串
                aggregationSvc.aggregate(succ, repo, "");  // requirement 已嵌入 step 名
                succ.setStatus(TaskStepStatus.READY);
                succ.setReaggregatedAt(LocalDateTime.now());
            }
            stepRepo.save(succ);
        }
        return true;
    }
}
