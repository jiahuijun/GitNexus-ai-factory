package com.factory.ai.task.service;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;
import com.factory.ai.task.mapper.TaskStepMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * 任务执行服务，实现"全自动执行"闭环：认领 → 调 LLM 生成代码 → 写文件 → 完成。
 *
 * <p>这是 AI Factory 执行层的核心。worker 调用 {@code POST /tasks/{id}/execute}
 * 后，本服务依次：
 * <ol>
 *   <li>{@link TaskClaimService#claim} 原子认领 READY 步骤（CAS）</li>
 *   <li>{@link LlmGateway#executeStep} 调 LLM，基于步骤的 generated_prompt 生成代码</li>
 *   <li>{@link #stripCodeFences} 剥离可能的 markdown 围栏</li>
 *   <li>{@link Files#writeString} 将代码写入 {@code repoBasePath/repo/targetFile}</li>
 *   <li>{@link TaskCompletionService#complete} 触发 detectChanges 验证 + DONE + 解锁后继</li>
 * </ol>
 *
 * <p>"不降级"原则：LLM 调用失败抛 {@link LlmException}，文件写入失败抛
 * {@link IOException}（包装为非受检），均触发事务回滚。</p>
 *
 * <p>注意：{@code @Transactional} 只管 DB 事务，文件写入不在事务内——
 * 写文件先于 complete 调用，{@code detectChanges} 能检测到磁盘变更。</p>
 */
@Service
public class TaskExecutionService {

    private final TaskClaimService claimService;
    private final TaskCompletionService completionService;
    private final LlmGateway llm;
    private final TaskStepMapper stepMapper;

    @Value("${factory.worker.repo-base-path:./repos}")
    private String repoBasePath;

    /** 最大重试次数，超过后步骤标记为 CANCELLED，防止无限重试烧钱。 */
    @Value("${factory.worker.max-retries:3}")
    private int maxRetries;

    public TaskExecutionService(TaskClaimService claimService,
            TaskCompletionService completionService, LlmGateway llm,
            TaskStepMapper stepMapper) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.llm = llm;
        this.stepMapper = stepMapper;
    }

    /**
     * 全自动执行一个任务步骤。
     *
     * <p>失败回退策略：
     * <ul>
     *   <li>LLM 调用 / 文件写入失败 → 抛异常，{@code @Transactional} 回滚撤销 claim，
     *       step 自动回到 READY</li>
     *   <li>{@code detectChanges} 未通过（complete 返回 false）→ 显式调用
     *       {@link TaskClaimService#revertClaim} 回退为 READY，返回 false</li>
     * </ul>
     *
     * @param stepId 要执行的步骤 id
     * @param userId worker 的用户 id
     * @param repo   目标仓库名（用于定位本地仓库路径）
     * @return true 表示执行并完成成功；false 表示认领失败或 detectChanges 未通过
     * @throws NoSuchElementException 若步骤不存在
     * @throws LlmException 若 LLM 调用失败（事务回滚，step 回到 READY）
     */
    @Transactional
    public boolean execute(Long stepId, Long userId, String repo) {
        // 1. 原子认领
        TaskStep step = claimService.claim(stepId, userId);
        if (step == null) return false;

        // 2. 调 LLM 生成代码（失败时 @Transactional 回滚，claim 自动撤销）
        String code = llm.executeStep(step.getGeneratedPrompt());
        code = stripCodeFences(code);

        // 3. 写文件到磁盘（失败时抛 RuntimeException，事务回滚）
        Path filePath = Path.of(repoBasePath, repo, step.getTargetFile());
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, code);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + filePath, e);
        }

        // 4. 完成：detectChanges 验证 + DONE + 解锁后继
        boolean ok = completionService.complete(stepId, userId, repo);
        if (!ok) {
            // detectChanges 未通过：递增重试次数
            step = stepMapper.selectById(stepId);
            step.incrementRetryCount();
            stepMapper.updateById(step);

            if (step.getRetryCount() >= maxRetries) {
                // 超过最大重试次数：标记 CANCELLED，不再重试
                step.setStatus(TaskStepStatus.CANCELLED);
                stepMapper.updateById(step);
            } else {
                // 仍有重试机会：回退为 READY
                claimService.revertClaim(stepId);
            }
            return false;
        }
        return true;
    }

    /**
     * 剥离 LLM 可能返回的 markdown 代码围栏。
     *
     * <p>LLM 有时会在输出首尾加上 ```java ... ``` 围栏，即使系统提示词禁止。
     * 本方法检测并剥离首尾围栏行，返回纯源代码。</p>
     *
     * @param raw LLM 原始输出
     * @return 剥离围栏后的纯代码
     */
    static String stripCodeFences(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String trimmed = raw.strip();
        // 匹配 ```java / ```python / ``` 等开头
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // 去掉末尾的 ```
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.strip();
        }
        return raw;
    }
}
