package com.factory.ai.task.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 任务依赖关系实体，表示步骤之间的有向边（DAG 边），构成步骤依赖图。
 *
 * <p>一条 {@code fromStepId -> toStepId} 的依赖表示 {@code toStep} 依赖 {@code fromStep}：
 * 即 {@code fromStep} 完成后，{@code toStep} 的待完成前置计数（{@link TaskStep#dependsOnCount}）
 * 才会递减；当计数归零时，{@code toStep} 状态从 PENDING 转为 READY，可被认领执行。
 *
 * <p>该实体使用复合主键（{@code fromStepId} + {@code toStepId}），由 {@code task_dependency} 表持久化。
 * MyBatis-Plus 不支持复合主键的 {@code selectById}，需通过 {@code LambdaQueryWrapper} 或自定义 SQL 查询。
 */
@TableName("task_dependency")
public class TaskDependency {
    /** 前置步骤 ID（被依赖的步骤），复合主键之一 */
    private Long fromStepId;
    /** 后继步骤 ID（依赖前者的步骤），复合主键之一 */
    private Long toStepId;

    /** 无参构造器 */
    public TaskDependency() {}

    /**
     * 创建依赖关系的便捷构造器。
     *
     * @param fromStepId 前置步骤 ID（被依赖的步骤）
     * @param toStepId   后继步骤 ID（依赖前者的步骤）
     */
    public TaskDependency(Long fromStepId, Long toStepId) {
        this.fromStepId = fromStepId; this.toStepId = toStepId;
    }

    /** @return 前置步骤 ID */
    public Long getFromStepId() { return fromStepId; }

    /** @param fromStepId 前置步骤 ID */
    public void setFromStepId(Long fromStepId) { this.fromStepId = fromStepId; }

    /** @return 后继步骤 ID */
    public Long getToStepId() { return toStepId; }

    /** @param toStepId 后继步骤 ID */
    public void setToStepId(Long toStepId) { this.toStepId = toStepId; }
}
