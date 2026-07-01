package com.factory.ai.task.domain;

/**
 * 任务步骤（子任务）状态枚举，构成 4+1 状态机。
 *
 * <p>状态流转：
 * <pre>
 *   PENDING --(dependsOnCount=0)--> READY --(claimTask)--> IN_PROGRESS --(complete)--> DONE
 *      |                                                                 |
 *      +----------------------------- CANCELLED ------------------------+
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING} —— 等待中：步骤已创建，但仍有未完成的前置依赖（dependsOnCount > 0），
 *       不能被执行。</li>
 *   <li>{@link #READY} —— 就绪：所有前置依赖已完成（dependsOnCount 归零），可被认领执行。</li>
 *   <li>{@link #IN_PROGRESS} —— 执行中：已被某个用户/AI 认领并开始执行（通过原子 claimTask 查询）。</li>
 *   <li>{@link #DONE} —— 完成：步骤执行完毕。</li>
 *   <li>{@link #CANCELLED} —— 已取消：步骤被中止，不再执行；其后续步骤的 dependsOnCount 也会相应递减。</li>
 * </ul>
 */
public enum TaskStepStatus {
    /** 等待中：仍有未完成的前置依赖 */
    PENDING,
    /** 就绪：前置依赖全部完成，可被认领 */
    READY,
    /** 执行中：已被认领并开始执行 */
    IN_PROGRESS,
    /** 完成：执行完毕 */
    DONE,
    /** 已取消：被中止 */
    CANCELLED
}
