package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.domain.Task;
import com.factory.ai.task.domain.TaskDependency;
import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.mapper.TaskDependencyMapper;
import com.factory.ai.task.mapper.TaskMapper;
import com.factory.ai.task.mapper.TaskStepMapper;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.service.TaskClaimService;
import com.factory.ai.task.service.TaskCompletionService;
import com.factory.ai.task.service.TaskDecompositionService;
import com.factory.ai.task.service.TaskExecutionService;
import com.factory.ai.task.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务流水线的 REST 控制器，对外暴露 {@code /tasks} 下的三个核心端点。
 *
 * <p>职责：
 * <ul>
 *   <li>{@code POST /tasks/decompose} —— 接收需求 + 仓库 + adminId，调用分解服务生成任务并返回任务 id；</li>
 *   <li>{@code POST /tasks/{id}/claim} —— worker 领取一个待执行的步骤，返回步骤与提示词，已被领取时返回 409；</li>
 *   <li>{@code POST /tasks/{id}/complete} —— worker 完成步骤，触发后继步骤的再聚合。</li>
 * </ul>
 *
 * <p>该控制器遵循「不降级」策略：上游（GitNexus / LLM）失败时统一映射为 503，
 * 而非 500，避免向调用方返回语义模糊的服务器内部错误。
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskDecompositionService decomp;
    private final TaskClaimService claim;
    private final TaskCompletionService complete;
    private final TaskExecutionService execution;
    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final TaskDependencyMapper depMapper;

    public TaskController(TaskDecompositionService decomp, TaskClaimService claim,
            TaskCompletionService complete, TaskExecutionService execution,
            TaskMapper taskMapper, TaskStepMapper stepMapper,
            TaskDependencyMapper depMapper) {
        this.decomp = decomp; this.claim = claim; this.complete = complete;
        this.execution = execution;
        this.taskMapper = taskMapper; this.stepMapper = stepMapper; this.depMapper = depMapper;
    }

    /**
     * 分解需求为任务步骤序列。
     *
     * @param req 分解请求体，包含需求文本、目标仓库与管理员 id
     * @return 200 OK，响应体为新创建任务的 id
     */
    @PostMapping("/decompose")
    public ResponseEntity<Long> decompose(@RequestBody DecomposeRequest req) {
        return ResponseEntity.ok(decomp.decompose(req.requirement(), req.repo(), req.adminId()));
    }

    /**
     * worker 领取任务下的一个待执行步骤。
     *
     * <p>语义：调用 {@link TaskClaimService#claim} 会原子地选取并占用一个 PENDING 步骤，
     * 返回携带生成提示词（prompt）的步骤。若返回 {@code null}，表示当前无可领取步骤
     * （例如已被其他 worker 抢占或任务已完结），此时返回 <strong>409 Conflict</strong>，
     * 提示调用方该任务目前不可领取，需稍后重试或查询状态。
     *
     * @param id  路径参数，目标任务 id
     * @param req 领取请求体，包含 worker 的 userId
     * @return 200 OK + {@link TaskStepResponse}（成功领取）；409 Conflict（无可领取步骤）
     */
    @PostMapping("/{id}/claim")
    public ResponseEntity<TaskStepResponse> claim(@PathVariable Long id, @RequestBody ClaimRequest req) {
        var step = claim.claim(id, req.userId());
        // step 为 null 表示步骤已被其他 worker 抢占或任务无可领取项 -> 409 冲突
        return step != null ? ResponseEntity.ok(TaskStepResponse.from(step))
                            : ResponseEntity.status(409).build();
    }

    /**
     * worker 完成任务下的一个步骤，触发后继步骤的再聚合。
     *
     * <p>当被完成的步骤 id 在系统中不存在（抛出 {@link NoSuchElementException}）时，
     * 不向调用方报错，而是返回 200 + {@code false}，便于 worker 以幂等方式重试完成请求。
     *
     * @param id  路径参数，目标任务 id
     * @param req 完成请求体，包含 worker 的 userId 与仓库地址
     * @return 200 OK + {@code true}（成功完成）；200 OK + {@code false}（步骤不存在，幂等返回）
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<Boolean> complete(@PathVariable Long id, @RequestBody CompleteRequest req) {
        try {
            boolean ok = complete.complete(id, req.userId(), req.repo());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return ResponseEntity.ok(false);
        }
    }

    /**
     * 全自动执行一个任务步骤（认领 → 调 LLM 生成代码 → 写文件 → 完成）。
     *
     * <p>worker 调用此端点后，系统自动完成整个执行闭环：
     * <ol>
     *   <li>原子认领 READY 步骤</li>
     *   <li>调 LLM 基于 generated_prompt 生成代码</li>
     *   <li>将代码写入目标文件</li>
     *   <li>触发 detectChanges 验证 + 标记 DONE + 解锁后继</li>
     * </ol>
     * 若步骤不存在或非 READY，返回 200 + false（幂等）。</p>
     *
     * @param id  路径参数，目标任务步骤 id
     * @param req 执行请求体，包含 worker 的 userId 与仓库名
     * @return 200 OK + true（成功）；200 OK + false（认领失败，幂等返回）
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<Boolean> execute(@PathVariable Long id, @RequestBody ExecuteRequest req) {
        try {
            boolean ok = execution.execute(id, req.userId(), req.repo());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return ResponseEntity.ok(false);
        }
    }

    // --- GET 查询端点 ---

    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(@RequestParam(required = false) String status) {
        List<Task> tasks = status != null
            ? taskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>()
                .eq(Task::getStatus, com.factory.ai.task.domain.TaskStatus.valueOf(status)))
            : taskMapper.selectList(null);
        return ResponseEntity.ok(tasks.stream().map(TaskResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        return t != null ? ResponseEntity.ok(TaskResponse.from(t))
                         : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/steps")
    public ResponseEntity<List<TaskStepSummary>> getSteps(@PathVariable Long id) {
        if (taskMapper.selectById(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
            stepMapper.findByTaskId(id).stream().map(TaskStepSummary::from).toList());
    }

    @GetMapping("/{id}/dependencies")
    public ResponseEntity<List<TaskDependencyResponse>> getDependencies(@PathVariable Long id) {
        if (taskMapper.selectById(id) == null) return ResponseEntity.notFound().build();
        var steps = stepMapper.findByTaskId(id);
        var stepNameMap = new java.util.HashMap<Long, String>();
        for (var s : steps) stepNameMap.put(s.getId(), s.getStepName());
        var deps = depMapper.findByTaskId(id);
        return ResponseEntity.ok(deps.stream().map(d -> new TaskDependencyResponse(
            d.getFromStepId(), d.getToStepId(),
            stepNameMap.get(d.getFromStepId()), stepNameMap.get(d.getToStepId())
        )).toList());
    }

    /**
     * 获取单个步骤的完整详情（含 generatedPrompt、designDetail 等所有字段）。
     *
     * <p>与 {@link #getSteps} 不同，此端点返回步骤的全部字段，
     * 供前端在不认领的情况下查看提示词、设计详情等内容。</p>
     *
     * @param stepId 步骤 ID（路径参数，注意是 stepId 而非 taskId）
     * @return 200 OK + TaskStep 完整 JSON；404 Not Found（步骤不存在）
     */
    @GetMapping("/steps/{stepId}")
    public ResponseEntity<TaskStep> getStepDetail(@PathVariable Long stepId) {
        TaskStep step = stepMapper.selectById(stepId);
        return step != null ? ResponseEntity.ok(step)
                             : ResponseEntity.notFound().build();
    }

    /**
     * 全局异常处理器：将上游（GitNexus / LLM）失败统一映射为 503 Service Unavailable。
     *
     * <p>「不降级」策略：当 {@link GitNexusException} 或 {@link LlmException} 抛出时，
     * 不返回 500 内部错误，而是返回 503 并附带结构化 {@link ErrorResponse}（code + message），
     * 明确告知调用方上游依赖暂时不可用，便于其重试或熔断。
     *
     * <p>注意：若上游调用发生在 {@code @Transactional} 事务中，事务回滚会先于异常到达此处理器
     * 执行（Spring 在事务通知层即完成 rollback），因此处理器收到异常时数据库状态已保持一致，
     * 无需在此额外处理事务。
     *
     * @param e 上游抛出的运行时异常（GitNexus 或 LLM 失败）
     * @return 503 + ErrorResponse（code=UPSTREAM_UNAVAILABLE，message=异常原因）
     */
    @ExceptionHandler({GitNexusException.class, LlmException.class})
    public ResponseEntity<ErrorResponse> onUpstreamFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
    }
}
