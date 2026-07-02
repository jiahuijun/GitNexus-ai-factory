package com.factory.ai.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.factory.ai.task.domain.TaskStep;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@link TaskStep} 步骤实体的 MyBatis-Plus Mapper。
 *
 * <p>除标准 CRUD 外，提供原子认领（claim）方法，用于在多用户/多 AI 实例并发场景下
 * 安全地将就绪步骤（READY）转入执行中（IN_PROGRESS）。
 */
public interface TaskStepMapper extends BaseMapper<TaskStep> {

    /**
     * 原子认领步骤：通过单条 UPDATE...WHERE 语句在数据库层面完成状态切换与认领人赋值，
     * 避免先查后改的竞态条件（race condition）。
     *
     * <p>只有当前状态仍为 {@code expectedStatus} 时才会更新成功，返回受影响行数
     * （1 表示认领成功，0 表示已被他人抢走或状态已变）。
     * 这种"比较并交换"（CAS）式写法天然防止并发重复认领。
     *
     * <p>注意：MyBatis-Plus 的 {@link com.baomidou.mybatisplus.annotation.Version}
     * 乐观锁拦截器会自动在 UPDATE 时追加 version 条件并递增 version，
     * 但此处使用原生 @Update 注解，需手动处理 version 递增。
     *
     * @param taskId         要认领的步骤 ID
     * @param userId         认领人 ID
     * @param expectedStatus 期望的当前状态（通常为 READY）
     * @param newStatus      认领后切换到的目标状态（通常为 IN_PROGRESS）
     * @return 受影响行数，1 表示认领成功，0 表示竞争失败
     */
    @Update("UPDATE task_step SET status = #{newStatus}, assignee_id = #{userId}, version = version + 1 " +
            "WHERE id = #{taskId} AND status = #{expectedStatus}")
    int claimTask(@Param("taskId") Long taskId,
                  @Param("userId") Long userId,
                  @Param("expectedStatus") String expectedStatus,
                  @Param("newStatus") String newStatus);

    /**
     * 按父任务 ID 与状态查询步骤列表。
     *
     * @param taskId  父任务 ID
     * @param status  目标状态
     * @return 匹配的步骤列表
     */
    @Select("SELECT * FROM task_step WHERE task_id = #{taskId} AND status = #{status}")
    List<TaskStep> findByTaskIdAndStatus(@Param("taskId") Long taskId,
                                         @Param("status") String status);

    @Select("SELECT * FROM task_step WHERE task_id = #{taskId} ORDER BY id")
    List<TaskStep> findByTaskId(@Param("taskId") Long taskId);

    /**
     * 回退认领：将 IN_PROGRESS 的步骤回退为 READY，清除认领人。
     *
     * <p>用于执行失败场景（detectChanges 未通过等），允许后续重试。
     * CAS 语义：仅当步骤仍为 IN_PROGRESS 时才回退，防止误操作已完成的步骤。</p>
     *
     * @param stepId         要回退的步骤 ID
     * @param expectedStatus 期望的当前状态（IN_PROGRESS）
     * @param newStatus      回退后的状态（READY）
     * @return 受影响行数，1 表示回退成功，0 表示状态已变
     */
    @Update("UPDATE task_step SET status = #{newStatus}, assignee_id = NULL, version = version + 1 " +
            "WHERE id = #{stepId} AND status = #{expectedStatus}")
    int revertClaim(@Param("stepId") Long stepId,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("newStatus") String newStatus);
}
