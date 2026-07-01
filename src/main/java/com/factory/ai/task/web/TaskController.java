package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.service.TaskClaimService;
import com.factory.ai.task.service.TaskCompletionService;
import com.factory.ai.task.service.TaskDecompositionService;
import com.factory.ai.task.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 构造函数注入三个核心服务。
     *
     * @param decomp   任务分解服务，负责将需求拆解为可执行的步骤序列
     * @param claim    任务领取服务，负责原子地分配下一个待执行步骤
     * @param complete 任务完成服务，负责标记步骤完成并触发后继步骤的再聚合
     */
    public TaskController(TaskDecompositionService decomp, TaskClaimService claim,
            TaskCompletionService complete) {
        this.decomp = decomp; this.claim = claim; this.complete = complete;
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
            // 步骤不存在视为已完成（幂等），返回 false 而非 4xx/5xx
            return ResponseEntity.ok(false);
        }
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
