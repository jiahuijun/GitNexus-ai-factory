package com.factory.ai.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.factory.ai.task.domain.Task;

/**
 * {@link Task} 父任务实体的 MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得标准 CRUD（insert、updateById、selectById、selectList 等），
 * 任务的状态流转与分解逻辑由上层服务编排，本 Mapper 暂不包含自定义查询。
 */
public interface TaskMapper extends BaseMapper<Task> {
}
