package com.factory.ai.chat.session;

import com.factory.ai.chat.mapper.ChatSessionMapper;
import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 基于 MySQL 的 {@link ChatSessionStore} 实现。
 *
 * <p>使用 {@link ChatSessionMapper}（MyBatis-Plus BaseMapper）进行 CRUD，
 * 会话持久化到 {@code chat_session} 表。JSON 字段（{@code queryResult}、
 * {@code history}）通过自定义 TypeHandler 自动序列化/反序列化。</p>
 *
 * <p>TTL 策略与 {@link InMemoryChatSessionStore} 一致：30 分钟未访问的会话
 * 在 {@link #get} 时惰性检查，过期则删除并返回 null。每次 {@code get} 和
 * {@code save} 都会刷新 {@code lastAccessedAt}。</p>
 */
@Component
public class DbChatSessionStore implements ChatSessionStore {

    /** 会话 TTL：30 分钟 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final ChatSessionMapper mapper;

    public DbChatSessionStore(ChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ChatSession create(String requirement, String repo, Long adminId, QueryResult queryResult) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(sessionId, requirement, repo, adminId, queryResult);
        mapper.insert(session);
        return session;
    }

    @Override
    public ChatSession get(String sessionId) {
        ChatSession session = mapper.selectById(sessionId);
        if (session == null) return null;
        // TTL 检查：超过 30 分钟未访问则视为过期
        if (session.getLastAccessedAt() == null ||
                Duration.between(session.getLastAccessedAt(), LocalDateTime.now()).compareTo(TTL) > 0) {
            mapper.deleteById(sessionId);
            return null;
        }
        // 刷新 lastAccessedAt
        session.setLastAccessedAt(LocalDateTime.now());
        mapper.updateById(session);
        return session;
    }

    @Override
    public void save(ChatSession session) {
        session.setLastAccessedAt(LocalDateTime.now());
        mapper.updateById(session);
    }

    @Override
    public void remove(String sessionId) {
        mapper.deleteById(sessionId);
    }
}
