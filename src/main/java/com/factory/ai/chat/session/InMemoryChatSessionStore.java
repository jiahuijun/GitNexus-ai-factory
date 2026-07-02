package com.factory.ai.chat.session;

import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link ChatSessionStore} 实现。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储会话，手动 TTL 检查（30 分钟过期）。
 * 不引入 Caffeine 等外部缓存依赖，保持轻量。</p>
 *
 * <p>线程安全：ConcurrentHashMap 保证并发读写安全；
 * TTL 检查在 {@link #get} 时惰性执行（不主动清理过期会话）。</p>
 */
@Component
public class InMemoryChatSessionStore implements ChatSessionStore {

    /** 会话 TTL：30 分钟（毫秒） */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, ChatSession> store = new ConcurrentHashMap<>();

    @Override
    public ChatSession create(String requirement, String repo, Long adminId, QueryResult queryResult) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(sessionId, requirement, repo, adminId, queryResult);
        store.put(sessionId, session);
        return session;
    }

    @Override
    public ChatSession get(String sessionId) {
        ChatSession session = store.get(sessionId);
        if (session == null) return null;
        // TTL 检查：超过 30 分钟未访问则视为过期
        if (System.currentTimeMillis() - session.getLastAccessedAt() > TTL_MILLIS) {
            store.remove(sessionId);
            return null;
        }
        session.setLastAccessedAt(System.currentTimeMillis());
        return session;
    }

    @Override
    public void save(ChatSession session) {
        session.setLastAccessedAt(System.currentTimeMillis());
        store.put(session.getSessionId(), session);
    }

    @Override
    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
