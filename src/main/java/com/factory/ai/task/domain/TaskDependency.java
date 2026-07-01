package com.factory.ai.task.domain;

import jakarta.persistence.*;

/**
 * 任务依赖关系实体，表示步骤之间的有向边（DAG 边），构成步骤依赖图。
 *
 * <p>一条 {@code fromStepId -> toStepId} 的依赖表示 {@code toStep} 依赖 {@code fromStep}：
 * 即 {@code fromStep} 完成后，{@code toStep} 的待完成前置计数（{@link TaskStep#dependsOnCount}）
 * 才会递减；当计数归零时，{@code toStep} 状态从 PENDING 转为 READY，可被认领执行。
 *
 * <p>该实体使用复合主键（{@link PK}），由 {@code fromStepId} 与 {@code toStepId} 共同唯一标识。
 * 复合键采用 {@link IdClass} 模式而非 {@code @EmbeddedId}，以便在 JPA 查询中直接引用两个外键字段。
 */
@Entity
@Table(name = "task_dependency")
@IdClass(TaskDependency.PK.class)
public class TaskDependency {
    /**
     * 复合主键类，由 {@code fromStepId} 和 {@code toStepId} 共同组成。
     *
     * <p>需实现 {@code Serializable} 以满足 JPA {@link IdClass} 的要求，
     * 并需正确重写 {@code equals} 与 {@code hashCode} 以保证持久化上下文中实体身份识别的正确性。
     */
    public static class PK implements java.io.Serializable {
        /** 依赖关系中的前置步骤 ID（被依赖的步骤） */
        public Long fromStepId;
        /** 依赖关系中的后继步骤 ID（依赖前者的步骤） */
        public Long toStepId;

        /** JPA 要求的无参构造器 */
        public PK() {}

        /**
         * 便捷构造器。
         *
         * @param f 前置步骤 ID
         * @param t 后继步骤 ID
         */
        public PK(Long f, Long t) { this.fromStepId = f; this.toStepId = t; }

        /**
         * 复合主键相等性判定，两个字段均需相等。
         */
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK p)) return false;
            return java.util.Objects.equals(fromStepId, p.fromStepId)
                && java.util.Objects.equals(toStepId, p.toStepId);
        }

        /**
         * 基于两个外键字段计算 hash，保证与 equals 一致。
         */
        @Override public int hashCode() { return java.util.Objects.hash(fromStepId, toStepId); }
    }

    /** 前置步骤 ID（被依赖的步骤），作为复合主键的一部分 */
    @Id private Long fromStepId;
    /** 后继步骤 ID（依赖前者的步骤），作为复合主键的一部分 */
    @Id private Long toStepId;

    /** JPA 要求的无参构造器 */
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

    /**
     * @return 前置步骤 ID
     */
    public Long getFromStepId() { return fromStepId; }

    /**
     * @return 后继步骤 ID
     */
    public Long getToStepId() { return toStepId; }
}
