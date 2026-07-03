package com.factory.ai.chat.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.factory.ai.gitnexus.dto.QueryResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话式需求澄清会话（MyBatis-Plus 实体，持久化到 {@code chat_session} 表）。
 *
 * <p>每个会话对应一次需求澄清流程：用户输入原始需求 → GitNexus 摸底（缓存）→
 * LLM 多轮澄清 → 确认拆解。会话持久化到 MySQL，带 30 分钟 TTL（惰性检查）。</p>
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@code CHAT} — 澄清进行中，用户与 LLM 交互</li>
 *   <li>{@code DECOMPOSED} — 用户确认拆解，已调用 TaskDecompositionService 生成任务</li>
 * </ol>
 *
 * <p>JSON 字段（{@code queryResult}、{@code history}）通过自定义 TypeHandler
 * 在 TEXT 列中存储 JSON 序列化文本。{@code autoResultMap = true} 确保读取时也使用 TypeHandler。</p>
 */
@TableName(value = "chat_session", autoResultMap = true)
public class ChatSession {

    /** 会话唯一 ID（UUID，应用层生成） */
    @TableId(type = IdType.INPUT)
    private String sessionId;
    /** 用户输入的原始需求 */
    private String originalRequirement;
    /** 目标仓库名 */
    private String repo;
    /** 管理员 ID */
    private Long adminId;
    /** GitNexus 摸底结果（在 start() 时调用一次后缓存，JSON 列） */
    @TableField(typeHandler = QueryResultTypeHandler.class)
    private QueryResult queryResult;
    /** 对话历史（user/assistant 交替，JSON 列） */
    @TableField(typeHandler = ChatMessageListTypeHandler.class)
    private List<ChatMessage> history = new ArrayList<>();
    /** 会话状态：CHAT | DECOMPOSED */
    private String state = "CHAT";
    /** 拆解后生成的任务 ID */
    private Long taskId;
    /** LLM 合成的精炼需求（ready=true 时设置） */
    private String refinedRequirement;
    /** 最后访问时间，用于 30 分钟 TTL 检查 */
    private LocalDateTime lastAccessedAt;
    /** 创建时间（DB DEFAULT CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

    public ChatSession() {}

    public ChatSession(String sessionId, String originalRequirement, String repo,
                       Long adminId, QueryResult queryResult) {
        this.sessionId = sessionId;
        this.originalRequirement = originalRequirement;
        this.repo = repo;
        this.adminId = adminId;
        this.queryResult = queryResult;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOriginalRequirement() { return originalRequirement; }
    public void setOriginalRequirement(String originalRequirement) { this.originalRequirement = originalRequirement; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }
    public QueryResult getQueryResult() { return queryResult; }
    public void setQueryResult(QueryResult queryResult) { this.queryResult = queryResult; }
    public List<ChatMessage> getHistory() { return history; }
    public void setHistory(List<ChatMessage> history) { this.history = history; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getRefinedRequirement() { return refinedRequirement; }
    public void setRefinedRequirement(String refinedRequirement) { this.refinedRequirement = refinedRequirement; }
    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
