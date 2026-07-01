package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务拆解编排服务，是 AI Factory 拆解流水线的核心入口。
 *
 * <p>将一条产品需求编排为完整的可执行任务图：建父任务 → GitNexus 摸底 →
 * LLM 拆解 → 建步骤 → 派生依赖 → 聚合上下文 → 设置状态。整个流程在
 * {@code @Transactional} 内，任何异常触发回滚，保证不产生半成品数据。</p>
 *
 * <p>"不降级"原则：上游（GitNexus / LLM）失败均抛非受检异常，
 * 直接回滚事务而非落库空数据。</p>
 *
 * @see DependencyDerivationService
 * @see ContextAggregationService
 * @see LlmGateway
 */
@Service
public class TaskDecompositionService {

    private final GitNexusClient gitNexus;
    private final LlmGateway llm;
    private final DependencyDerivationService derivationSvc;
    private final ContextAggregationService aggregationSvc;
    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final TaskDependencyMapper depMapper;

    /**
     * 构造编排服务，注入所有协作组件。
     *
     * @param gitNexus       GitNexus 客户端，第 2 步摸底
     * @param llm            LLM 网关，第 3 步拆解
     * @param derivationSvc  依赖派生服务，第 5 步
     * @param aggregationSvc 上下文聚合服务，第 6 步
     * @param taskMapper       父任务 Mapper
     * @param stepMapper       任务步骤 Mapper
     * @param depMapper        依赖关系 Mapper
     */
    public TaskDecompositionService(GitNexusClient gitNexus, LlmGateway llm,
            DependencyDerivationService derivationSvc, ContextAggregationService aggregationSvc,
            TaskMapper taskMapper, TaskStepMapper stepMapper, TaskDependencyMapper depMapper) {
        this.gitNexus = gitNexus; this.llm = llm;
        this.derivationSvc = derivationSvc; this.aggregationSvc = aggregationSvc;
        this.taskMapper = taskMapper; this.stepMapper = stepMapper; this.depMapper = depMapper;
    }

    /**
     * 编排七步拆解流水线，将需求转化为 READY/PENDING 的任务图。
     *
     * <p>七步流程：
     * <ol>
     *   <li><b>建父任务</b>：持久化 Task(requirement, adminId)</li>
     *   <li><b>query 摸底</b>：调 GitNexus query() 获取相关符号与执行流</li>
     *   <li><b>LLM 拆解</b>：调 LlmGateway.splitTasks() 得到任务草稿列表</li>
     *   <li><b>空草稿早返回</b>：草稿为空 → 父任务标 DECOMPOSING_FAILED，直接返回
     *       （不建 step，避免无效数据）</li>
     *   <li><b>建步骤实体</b>：将草稿转为 TaskStep 并入库（拿到 id）</li>
     *   <li><b>派生依赖</b>：调 DependencyDerivationService 推导 DAG，就地修改 dependsOnCount</li>
     *   <li><b>聚合上下文</b>：对每个 step 调 ContextAggregationService 拉 Prompt，
     *       并按 dependsOnCount 设置 READY / PENDING；最后根据 needsReview 设置父任务状态</li>
     * </ol>
     * </p>
     *
     * @param requirement 管理员提交的产品需求文本
     * @param repo        目标仓库名称
     * @param adminId     提交需求的管理员 id
     * @return 父任务 Task id
     * @throws LlmException        当 LLM 拆解失败时抛出
     * @throws GitNexusException   当 GitNexus 摸底失败时抛出
     */
    @Transactional
    public Long decompose(String requirement, String repo, Long adminId) {
        // 1. 建父任务
        Task task = new Task(requirement, adminId);
        taskMapper.insert(task);

        // 2. query 摸底：获取相关符号与执行流，供 LLM 选取 targetSymbol
        QueryResult queryResult = gitNexus.query(requirement, repo);

        // 3. LLM 拆解：基于需求 + 摸底结果输出任务草稿
        List<LlmGateway.TaskDraft> drafts = llm.splitTasks(requirement, queryResult);

        // 3.5 空草稿早返回：摸底无相关符号或 LLM 判定无需拆解 → DECOMPOSING_FAILED，不建 step
        if (drafts.isEmpty()) {
            task.setStatus(TaskStatus.DECOMPOSING_FAILED);
            taskMapper.updateById(task);
            return task.getId();
        }

        // 4. 建步骤实体（先存，拿到 ID 供后续派生依赖与聚合使用）
        List<TaskStep> stepList = new ArrayList<>();
        for (var d : drafts) {
            TaskStep s = new TaskStep(task.getId(), d.stepName(), d.targetSymbol());
            s.setDesignDetail(d.designDetail());
            stepMapper.insert(s);
            stepList.add(s);
        }

        // 5. 派生依赖（就地修改 dependsOnCount），返回依赖边集
        var edges = derivationSvc.derive(stepList, repo);
        for (var edge : edges) {
            depMapper.insert(edge);
        }

        // 6. 初始上下文聚合：为每个 step 拉 Prompt，并按依赖数设置状态
        for (TaskStep s : stepList) {
            aggregationSvc.aggregate(s, repo, requirement);
            // 依赖为 0 → READY 可认领；否则 PENDING 等待前驱完成
            s.setStatus(s.getDependsOnCount() == 0 ? TaskStepStatus.READY : TaskStepStatus.PENDING);
            stepMapper.updateById(s);
        }

        // 7. 父任务就绪：有 needs_review 的 step → PARTIAL，否则 READY
        boolean anyReview = stepList.stream().anyMatch(TaskStep::isNeedsReview);
        task.setStatus(anyReview ? TaskStatus.PARTIAL : TaskStatus.READY);
        taskMapper.updateById(task);
        return task.getId();
    }
}
