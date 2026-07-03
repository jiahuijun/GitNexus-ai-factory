package com.factory.ai.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.factory.ai.chat.session.ChatSession;

/**
 * {@link ChatSession} 的 MyBatis-Plus Mapper。
 *
 * <p>仅继承 {@link BaseMapper} 提供的标准 CRUD（insert / selectById / updateById / deleteById），
 * 不添加自定义 @Select / @Update 查询——自定义查询不会使用 {@code autoResultMap = true}
 * 生成的 ResultMap，会导致 TypeHandler 在读取路径上失效。</p>
 */
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
