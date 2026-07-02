package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.domain.Task;
import com.factory.ai.task.domain.TaskDependency;
import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.mapper.TaskDependencyMapper;
import com.factory.ai.task.mapper.TaskMapper;
import com.factory.ai.task.mapper.TaskStepMapper;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.service.TaskCancellationService;
import com.factory.ai.task.service.TaskClaimService;
import com.factory.ai.task.service.TaskCompletionService;
import com.factory.ai.task.service.TaskDecompositionService;
import com.factory.ai.task.service.TaskExecutionService;
import com.factory.ai.task.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    private final TaskCancellationService cancellation;
    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final TaskDependencyMapper depMapper;

    public TaskController(TaskDecompositionService decomp, TaskClaimService claim,
            TaskCompletionService complete, TaskExecutionService execution,
            TaskCancellationService cancellation,
            TaskMapper taskMapper, TaskStepMapper stepMapper,
            TaskDependencyMapper depMapper) {
        this.decomp = decomp; this.claim = claim; this.complete = complete;
        this.execution = execution; this.cancellation = cancellation;
        this.taskMapper = taskMapper; this.stepMapper = stepMapper; this.depMapper = depMapper;
    }

    /**
     * 分解需求为任务步骤序列。
     *
     * @param req 分解请求体，包含需求文本、目标仓库与管理员 id
     * @return 200 OK，响应体为新创建任务的 id
     */
    @PostMapping("/decompose")
    public ResponseEntity<Long> decompose(@Valid @RequestBody DecomposeRequest req) {
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
    public ResponseEntity<TaskStepResponse> claim(@PathVariable Long id, @Valid @RequestBody ClaimRequest req) {
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
    public ResponseEntity<Boolean> complete(@PathVariable Long id, @Valid @RequestBody CompleteRequest req) {
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
    public ResponseEntity<Boolean> execute(@PathVariable Long id, @Valid @RequestBody ExecuteRequest req) {
        try {
            boolean ok = execution.execute(id, req.userId(), req.repo());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return ResponseEntity.ok(false);
        }
    }

    // --- GET 查询端点 ---

    /**
     * 取消整个任务：所有未完成步骤 → CANCELLED，任务 → CANCELLED。
     *
     * <p>用于任务需要中止的场景。已 DONE 的步骤不受影响。
     * 取消的步骤会递减后继的 dependsOnCount，归零的后继变为 READY。</p>
     *
     * @param id 任务 ID
     * @return 200 OK（取消成功）；404 Not Found（任务不存在）
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelTask(@PathVariable Long id) {
        try {
            cancellation.cancelTask(id);
            return ResponseEntity.ok().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 释放单个卡住的 IN_PROGRESS 步骤，回退为 READY。
     *
     * <p>用于 Worker 崩溃后恢复：步骤卡在 IN_PROGRESS 状态，
     * 释放后清除认领人，可被其他 Worker 重新认领。</p>
     *
     * @param stepId 步骤 ID
     * @return 200 OK + true（释放成功）；200 OK + false（步骤不存在或非 IN_PROGRESS）
     */
    @PostMapping("/steps/{stepId}/release")
    public ResponseEntity<Boolean> releaseStep(@PathVariable Long stepId) {
        return ResponseEntity.ok(cancellation.releaseStep(stepId));
    }

    @GetMapping
    public ResponseEntity<PageResponse<TaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        var queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>();
        if (status != null) {
            queryWrapper.eq(Task::getStatus, com.factory.ai.task.domain.TaskStatus.valueOf(status));
        }
        queryWrapper.orderByDesc(Task::getId);
        var pageResult = taskMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), queryWrapper);
        var items = pageResult.getRecords().stream().map(TaskResponse::from).toList();
        return ResponseEntity.ok(PageResponse.of(items, pageResult.getTotal(), page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        return t != null ? ResponseEntity.ok(TaskResponse.from(t))
                         : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/steps")
    public ResponseEntity<PageResponse<TaskStepSummary>> getSteps(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (taskMapper.selectById(id) == null) return ResponseEntity.notFound().build();
        var allSteps = stepMapper.findByTaskId(id);
        int total = allSteps.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        var pageItems = allSteps.subList(from, to).stream().map(TaskStepSummary::from).toList();
        return ResponseEntity.ok(PageResponse.of(pageItems, total, page, size));
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
     * 查询某个用户已认领（IN_PROGRESS）的步骤列表。
     *
     * <p>worker 在一个 Claude Code 中认领任务后，可在另一个 Claude Code 中
     * 通过此端点查询自己的待办步骤，获取完整 prompt 后直接开始开发。</p>
     *
     * @param userId 用户 ID
     * @return 已认领的步骤列表（含 generatedPrompt 等完整字段）
     */
    @GetMapping("/steps/claimed")
    public ResponseEntity<List<TaskStep>> getClaimedByUser(@RequestParam Long userId) {
        return ResponseEntity.ok(stepMapper.findClaimedByUser(userId));
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

    /**
     * 输入校验失败 → 400 Bad Request。
     *
     * <p>当 @Valid 校验不通过时，返回结构化错误信息，列出所有违规字段及原因。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(fe.getField()).append(": ").append(fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_FAILED", sb.toString()));
    }
}
