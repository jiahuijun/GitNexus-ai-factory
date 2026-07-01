package com.factory.ai.task.domain;

import jakarta.persistence.*;

/**
 * 任务实体，表示一个父级需求（例如"加VIP等级查询"）。
 *
 * <p>一个 Task 会被拆解为多个 {@link TaskStep}（开发子任务），并通过 {@link TaskStatus}
 * 状态机管理整体生命周期：从分解中（DECOMPOSING）到就绪（READY）、部分完成（PARTIAL）、
 * 最终完成（DONE）或取消（CANCELLED）。
 *
 * <p>该实体由 {@code task} 表持久化，主键自增，需求描述以 LOB 形式存储以支持较长文本。
 */
@Entity
@Table(name = "task")
public class Task {
    /** 主键，数据库自增 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始需求描述文本，使用 LOB 以承载较长内容 */
    @Lob private String requirement;

    /**
     * 任务整体状态，默认从分解中开始。
     * 使用字符串枚举持久化以便于人工查看数据库。
     */
    @Enumerated(EnumType.STRING) private TaskStatus status = TaskStatus.DECOMPOSING;

    /** 创建人用户 ID */
    private Long createdBy;

    /** JPA 要求的无参构造器 */
    public Task() {}

    /**
     * 创建新任务的便捷构造器（状态默认为 DECOMPOSING）。
     *
     * @param requirement 需求描述文本
     * @param createdBy    创建人用户 ID
     */
    public Task(String requirement, Long createdBy) {
        this.requirement = requirement; this.createdBy = createdBy;
    }

    /**
     * @return 主键 ID
     */
    public Long getId() { return id; }

    /**
     * @return 原始需求描述文本
     */
    public String getRequirement() { return requirement; }

    /**
     * @return 当前任务状态
     */
    public TaskStatus getStatus() { return status; }

    /**
     * 设置任务状态。
     *
     * @param s 新状态
     */
    public void setStatus(TaskStatus s) { this.status = s; }

    /**
     * @return 创建人用户 ID
     */
    public Long getCreatedBy() { return createdBy; }
}
