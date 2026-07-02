package com.factory.ai.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.factory.ai.task.domain.TaskDependency;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@link TaskDependency} 步骤依赖关系实体的 MyBatis-Plus Mapper。
 *
 * <p>复合主键为 {@code fromStepId} + {@code toStepId}，MyBatis-Plus 不支持复合主键的
 * {@code selectById}，需通过自定义 SQL 或 LambdaQueryWrapper 查询。
 *
 * <p>主要用于查询某个步骤的后继依赖，以便在前置步骤完成时递减后继步骤的 dependsOnCount。
 */
public interface TaskDependencyMapper extends BaseMapper<TaskDependency> {

    /**
     * 按前置步骤 ID 查询所有依赖关系，即找出所有"依赖该前置步骤"的后继步骤。
     * <p>典型用法：当 {@code fromStepId} 完成时，遍历返回结果，对每个 {@code toStepId}
     * 调用 {@link com.factory.ai.task.domain.TaskStep#decrementDependsOnCount()}。
     *
     * @param fromStepId 前置步骤 ID
     * @return 以该步骤为前置的所有依赖关系列表
     */
    @Select("SELECT * FROM task_dependency WHERE from_step_id = #{fromStepId}")
    List<TaskDependency> findByFromStepId(@Param("fromStepId") Long fromStepId);

    @Select("SELECT * FROM task_dependency WHERE from_step_id IN " +
            "(SELECT id FROM task_step WHERE task_id = #{taskId}) OR to_step_id IN " +
            "(SELECT id FROM task_step WHERE task_id = #{taskId})")
    List<TaskDependency> findByTaskId(@Param("taskId") Long taskId);
}
