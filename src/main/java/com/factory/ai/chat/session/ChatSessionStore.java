package com.factory.ai.chat.session;

import com.factory.ai.gitnexus.dto.QueryResult;

/**
 * 对话澄清会话存储接口。
 *
 * <p>实现类负责会话的 CRUD 与 TTL 管理。默认实现 {@link DbChatSessionStore}
 * 使用 MySQL 持久化存储；{@link InMemoryChatSessionStore} 作为参考实现保留供测试使用。</p>
 */
public interface ChatSessionStore {

    /**
     * 创建新会话并存储。
     *
     * @param requirement 原始需求
     * @param repo        仓库名
     * @param adminId     管理员 ID
     * @param queryResult GitNexus 摸底结果（缓存）
     * @return 新创建的会话（含生成的 sessionId）
     */
    ChatSession create(String requirement, String repo, Long adminId, QueryResult queryResult);

    /**
     * 按 sessionId 获取会话。
     *
     * <p>如果会话不存在或已过期（超过 TTL），返回 null。
     * 访问会更新 lastAccessedAt。</p>
     *
     * @param sessionId 会话 ID
     * @return 会话对象；不存在或过期返回 null
     */
    ChatSession get(String sessionId);

    /**
     * 保存（更新）会话。
     *
     * @param session 要保存的会话
     */
    void save(ChatSession session);

    /**
     * 删除会话。
     *
     * @param sessionId 会话 ID
     */
    void remove(String sessionId);
}
