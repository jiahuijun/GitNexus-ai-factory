package com.factory.ai.task.domain;

/**
 * 父任务（{@link Task}）整体状态枚举，描述任务从创建到结束的生命周期阶段。
 *
 * <ul>
 *   <li>{@link #DECOMPOSING} —— 分解中：刚创建，AI 正在将需求拆解为多个 {@link TaskStep} 子任务。</li>
 *   <li>{@link #READY} —— 就绪：所有子任务已生成且依赖关系已建立，可开始执行。</li>
 *   <li>{@link #PARTIAL} —— 部分完成：部分子任务已完成，但仍有未完成项，执行中。</li>
 *   <li>{@link #DONE} —— 完成：所有子任务均已完成，整个任务结束。</li>
 *   <li>{@link #CANCELLED} —— 已取消：任务被中止，不再执行。</li>
 *   <li>{@link #DECOMPOSING_FAILED} —— 分解失败：AI 在拆解阶段出错，需求未能转化为可执行的子任务。</li>
 * </ul>
 */
public enum TaskStatus {
    /** 分解中：AI 正在将需求拆解为子任务 */
    DECOMPOSING,
    /** 就绪：子任务全部生成且依赖关系建立完毕，可执行 */
    READY,
    /** 部分完成：部分子任务已完成，仍有未完成项 */
    PARTIAL,
    /** 完成：所有子任务均已完成 */
    DONE,
    /** 已取消：任务被中止 */
    CANCELLED,
    /** 分解失败：AI 拆解阶段出错 */
    DECOMPOSING_FAILED
}
