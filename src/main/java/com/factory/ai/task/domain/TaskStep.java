package com.factory.ai.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 任务步骤实体，表示父任务（{@link Task}）拆解后的一个具体开发子任务。
 *
 * <p>每个步骤有：
 * <ul>
 *   <li>归属的父任务 ID（{@code taskId}）</li>
 *   <li>目标符号 / 目标文件，指明本步骤要修改或新增的代码位置</li>
 *   <li>状态机字段 {@code status}（4+1 状态，见 {@link TaskStepStatus}）</li>
 *   <li>{@code dependsOnCount}：未完成的前置步骤数，归零时从 PENDING 跃迁为 READY</li>
 *   <li>{@code version}：乐观锁版本号，防止并发修改冲突</li>
 *   <li>上下文快照、生成提示词等 AI 辅助字段</li>
 * </ul>
 *
 * <p>该实体由 {@code task_step} 表持久化。
 */
@TableName("task_step")
public class TaskStep {
    /** 主键，数据库自增 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属父任务 ID */
    private Long taskId;

    /** 步骤名称（简要描述本步骤要做什么） */
    private String stepName;

    /** 目标代码符号（如类名、方法名），用于定位修改位置 */
    private String targetSymbol;

    /** 目标文件路径，进一步精确定位修改位置 */
    private String targetFile;

    /** 步骤状态，默认 PENDING（等待前置依赖完成） */
    private TaskStepStatus status = TaskStepStatus.PENDING;

    /** 认领人（用户或 AI 实例）ID */
    private Long assigneeId;

    /**
     * 未完成的前置步骤计数。
     * <p>当某个前置步骤完成或取消时递减；归零时本步骤从 PENDING 跃迁为 READY，可被认领。
     */
    private int dependsOnCount;

    /**
     * 乐观锁版本号。
     * <p>MyBatis-Plus 通过 {@link Version} 注解 + {@code OptimisticLockerInnerInterceptor}
     * 在 update 时自动校验版本并递增，防止丢失更新。
     */
    @Version private int version;

    /**
     * 上下文快照。
     * <p>记录本步骤执行前相关代码/依赖的快照，供 AI 生成提示词或事后审计使用。
     */
    private String contextSnapshot;

    /**
     * AI 生成的执行提示词。
     * <p>基于上下文快照和步骤信息由 AI 生成，指导具体执行。
     */
    private String generatedPrompt;

    /**
     * LLM 输出的详细设计方案。
     * <p>包含具体的产出物类名、方法名、方法签名、实现思路（伪代码或关键步骤），
     * 由 LLM 拆解时输出，存入 {@code design_detail} 列，
     * 供 {@code PromptAssemblyService} 组装执行提示词时嵌入"设计详情"区。
     */
    private String designDetail;

    /**
     * 上下文重新聚合时间。
     * <p>当依赖步骤完成后，可能需要重新聚合上下文以更新提示词；该字段记录最近一次重新聚合时间。
     */
    private LocalDateTime reaggregatedAt;

    /** 是否需要人工复核（如 AI 执行结果不确定时置为 true） */
    private boolean needsReview;

    /** 失败重试次数。每次 detectChanges 失败时递增，超过阈值（默认 3）则标记 CANCELLED */
    private int retryCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /** 无参构造器 */
    public TaskStep() {}

    /**
     * 创建步骤的便捷构造器（状态默认 PENDING，dependsOnCount 默认 0）。
     *
     * @param taskId        所属父任务 ID
     * @param stepName      步骤名称
     * @param targetSymbol  目标代码符号
     */
    public TaskStep(Long taskId, String stepName, String targetSymbol) {
        this.taskId = taskId; this.stepName = stepName; this.targetSymbol = targetSymbol;
    }

    /** @return 主键 ID */
    public Long getId() { return id; }

    /** @param id 主键 ID */
    public void setId(Long id) { this.id = id; }

    /** @return 目标代码符号 */
    public String getTargetSymbol() { return targetSymbol; }

    /** @return 目标文件路径 */
    public String getTargetFile() { return targetFile; }

    /** @param f 目标文件路径 */
    public void setTargetFile(String f) { this.targetFile = f; }

    /** @return 当前步骤状态 */
    public TaskStepStatus getStatus() { return status; }

    /** @param s 新状态 */
    public void setStatus(TaskStepStatus s) { this.status = s; }

    /** @return 未完成的前置步骤计数 */
    public int getDependsOnCount() { return dependsOnCount; }

    /** @param c 前置步骤计数 */
    public void setDependsOnCount(int c) { this.dependsOnCount = c; }

    /**
     * 递减前置步骤计数。
     * <p>当某个前置步骤完成或取消时调用；归零时调用方应将状态从 PENDING 切到 READY。
     */
    public void decrementDependsOnCount() { this.dependsOnCount--; }

    /** @return AI 生成的提示词 */
    public String getGeneratedPrompt() { return generatedPrompt; }

    /** @param p 提示词 */
    public void setGeneratedPrompt(String p) { this.generatedPrompt = p; }

    /** @return 上下文快照 */
    public String getContextSnapshot() { return contextSnapshot; }

    /** @param c 上下文快照 */
    public void setContextSnapshot(String c) { this.contextSnapshot = c; }

    /** @return LLM 输出的详细设计方案 */
    public String getDesignDetail() { return designDetail; }

    /** @param d 详细设计方案 */
    public void setDesignDetail(String d) { this.designDetail = d; }

    /** @param t 上下文重新聚合时间 */
    public void setReaggregatedAt(LocalDateTime t) { this.reaggregatedAt = t; }

    /** @return 上下文重新聚合时间 */
    public LocalDateTime getReaggregatedAt() { return reaggregatedAt; }

    /** @return 是否需要人工复核 */
    public boolean isNeedsReview() { return needsReview; }

    /** @param v 是否需要人工复核 */
    public void setNeedsReview(boolean v) { this.needsReview = v; }

    /** @return 失败重试次数 */
    public int getRetryCount() { return retryCount; }

    /** @param retryCount 失败重试次数 */
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    /** 递增重试次数 */
    public void incrementRetryCount() { this.retryCount++; }

    /** @return 所属父任务 ID */
    public Long getTaskId() { return taskId; }

    /** @return 步骤名称 */
    public String getStepName() { return stepName; }

    /** @return 认领人 ID */
    public Long getAssigneeId() { return assigneeId; }

    /** @param a 认领人 ID */
    public void setAssigneeId(Long a) { this.assigneeId = a; }

    /** @return 乐观锁版本号 */
    public int getVersion() { return version; }

    /** @param version 乐观锁版本号 */
    public void setVersion(int version) { this.version = version; }
}
