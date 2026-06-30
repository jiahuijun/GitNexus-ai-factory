package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TaskDecompositionService {

    private final GitNexusClient gitNexus;
    private final LlmGateway llm;
    private final DependencyDerivationService derivationSvc;
    private final ContextAggregationService aggregationSvc;
    private final TaskRepository taskRepo;
    private final TaskStepRepository stepRepo;
    private final TaskDependencyRepository depRepo;

    public TaskDecompositionService(GitNexusClient gitNexus, LlmGateway llm,
            DependencyDerivationService derivationSvc, ContextAggregationService aggregationSvc,
            TaskRepository taskRepo, TaskStepRepository stepRepo, TaskDependencyRepository depRepo) {
        this.gitNexus = gitNexus; this.llm = llm;
        this.derivationSvc = derivationSvc; this.aggregationSvc = aggregationSvc;
        this.taskRepo = taskRepo; this.stepRepo = stepRepo; this.depRepo = depRepo;
    }

    @Transactional
    public Long decompose(String requirement, String repo, Long adminId) {
        // 1. 建父任务
        Task task = taskRepo.save(new Task(requirement, adminId));

        // 2. query 摸底
        QueryResult queryResult = gitNexus.query(requirement, repo);

        // 3. LLM 拆解
        List<LlmGateway.TaskDraft> drafts = llm.splitTasks(requirement, queryResult);

        // 4. 建步骤实体（先存，拿到 ID）
        List<TaskStep> stepList = new ArrayList<>();
        for (var d : drafts) {
            TaskStep s = new TaskStep(task.getId(), d.stepName(), d.targetSymbol());
            stepList.add(stepRepo.save(s));
        }

        // 5. 派生依赖（就地修改 dependsOnCount）
        var edges = derivationSvc.derive(stepList, repo);
        depRepo.saveAll(edges);

        // 6. 初始上下文聚合
        for (TaskStep s : stepList) {
            aggregationSvc.aggregate(s, repo, requirement);
            s.setStatus(s.getDependsOnCount() == 0 ? TaskStepStatus.READY : TaskStepStatus.PENDING);
            stepRepo.save(s);
        }

        // 7. 父任务就绪
        boolean anyReview = stepList.stream().anyMatch(TaskStep::isNeedsReview);
        task.setStatus(anyReview ? TaskStatus.PARTIAL : TaskStatus.READY);
        taskRepo.save(task);
        return task.getId();
    }
}
