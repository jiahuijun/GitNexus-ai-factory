package com.factory.ai.chat.service;

import com.factory.ai.chat.session.ChatMessage;
import com.factory.ai.chat.session.ChatSession;
import com.factory.ai.chat.session.ChatSessionStore;
import com.factory.ai.chat.web.dto.DecomposeResponse;
import com.factory.ai.chat.web.dto.GetSessionResponse;
import com.factory.ai.chat.web.dto.MessageResponse;
import com.factory.ai.chat.web.dto.PreviewResponse;
import com.factory.ai.chat.web.dto.StartSessionResponse;
import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.task.service.LlmGateway;
import com.factory.ai.task.service.TaskDecompositionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 对话式需求澄清编排服务。
 *
 * <p>将原本一次性"提交需求 → 直接拆解"的流程，改为"提交需求 → GitNexus 摸底 →
 * LLM 多轮澄清 → 用户确认 → 拆解"的交互式流程。GitNexus 摸底只在会话开始时调用一次，
 * 结果缓存在 {@link ChatSession} 中供后续轮次复用。</p>
 *
 * <p>三个核心方法对应三个 REST 端点：
 * <ul>
 *   <li>{@link #start} — 创建会话，摸底 + 生成第一个澄清问题</li>
 *   <li>{@link #sendMessage} — 追加用户回答，调 LLM 生成下一个问题或标记 ready</li>
 *   <li>{@link #decompose} — 用精炼需求调用 {@link TaskDecompositionService#decompose}</li>
 * </ul>
 *
 * <p>"不降级"原则：GitNexus / LLM 失败时抛出非受检异常，由控制器异常处理器映射为 503。</p>
 */
@Service
public class ChatClarificationService {

    private final GitNexusClient gitNexus;
    private final LlmGateway llm;
    private final ChatSessionStore store;
    private final TaskDecompositionService decompService;

    public ChatClarificationService(GitNexusClient gitNexus, LlmGateway llm,
            ChatSessionStore store, TaskDecompositionService decompService) {
        this.gitNexus = gitNexus;
        this.llm = llm;
        this.store = store;
        this.decompService = decompService;
    }

    /**
     * 开始澄清会话：GitNexus 摸底 + LLM 生成第一个问题。
     *
     * @param requirement 原始需求
     * @param repo        仓库名
     * @param adminId     管理员 ID
     * @return 会话 ID + 第一个澄清问题
     */
    public StartSessionResponse start(String requirement, String repo, Long adminId) {
        // 1. GitNexus 摸底（只调一次）
        QueryResult queryResult = gitNexus.query(requirement, repo);
        // 2. 创建会话
        ChatSession session = store.create(requirement, repo, adminId, queryResult);
        // 3. LLM 生成第一个问题
        LlmGateway.ClarifyReply reply = llm.clarify(requirement, queryResult, List.of());
        session.getHistory().add(new ChatMessage("assistant", reply.message()));
        if (reply.ready() && reply.refinedRequirement() != null) {
            session.setRefinedRequirement(reply.refinedRequirement());
        }
        store.save(session);
        return new StartSessionResponse(session.getSessionId(), reply.message(), reply.ready());
    }

    /**
     * 发送消息：追加用户回答 + LLM 生成下一个问题。
     *
     * @param sessionId 会话 ID
     * @param text      用户回答
     * @return LLM 回复 + ready 标志
     * @throws NoSuchElementException 会话不存在或已过期
     */
    public MessageResponse sendMessage(String sessionId, String text) {
        ChatSession session = store.get(sessionId);
        if (session == null) throw new NoSuchElementException("Session expired or not found");
        // 追加用户消息
        session.getHistory().add(new ChatMessage("user", text));
        // LLM 回复（复用缓存的摸底结果，不重新调 GitNexus）
        LlmGateway.ClarifyReply reply = llm.clarify(
            session.getOriginalRequirement(), session.getQueryResult(), session.getHistory());
        session.getHistory().add(new ChatMessage("assistant", reply.message()));
        if (reply.ready() && reply.refinedRequirement() != null) {
            session.setRefinedRequirement(reply.refinedRequirement());
        }
        store.save(session);
        return new MessageResponse(reply.message(), reply.ready());
    }

    /**
     * 确认拆解：用精炼需求（或回退到原始需求）调用拆解服务。
     * 若用户提供了修改后的 drafts，跳过 LLM 直接入库。
     *
     * @param sessionId 会话 ID
     * @param drafts    用户修改后的草稿（null 时由拆解服务调 LLM 生成）
     * @return 生成的任务 ID
     * @throws NoSuchElementException 会话不存在或已过期
     */
    public DecomposeResponse decompose(String sessionId, List<LlmGateway.TaskDraft> drafts) {
        ChatSession session = store.get(sessionId);
        if (session == null) throw new NoSuchElementException("Session expired or not found");
        String requirement = session.getRefinedRequirement() != null
            ? session.getRefinedRequirement()
            : session.getOriginalRequirement();
        Long taskId = decompService.decompose(requirement, session.getRepo(), session.getAdminId(), drafts);
        session.setState("DECOMPOSED");
        session.setTaskId(taskId);
        store.save(session);
        return new DecomposeResponse(taskId);
    }

    /**
     * 获取会话状态（用于前端恢复对话界面）。
     *
     * @param sessionId 会话 ID
     * @return 会话状态快照
     * @throws NoSuchElementException 会话不存在或已过期
     */
    public GetSessionResponse getSession(String sessionId) {
        ChatSession session = store.get(sessionId);
        if (session == null) throw new NoSuchElementException("Session expired or not found");
        boolean ready = session.getRefinedRequirement() != null;
        return new GetSessionResponse(
            session.getSessionId(),
            session.getOriginalRequirement(),
            session.getHistory(),
            ready,
            session.getState(),
            session.getTaskId()
        );
    }

    /**
     * 预览拆解结果：调用 LLM splitTasks 但不入库，供用户确认。
     *
     * @param sessionId 会话 ID
     * @return 任务草稿列表
     * @throws NoSuchElementException 会话不存在或已过期
     */
    public PreviewResponse preview(String sessionId) {
        ChatSession session = store.get(sessionId);
        if (session == null) throw new NoSuchElementException("Session expired or not found");
        String requirement = session.getRefinedRequirement() != null
            ? session.getRefinedRequirement()
            : session.getOriginalRequirement();
        List<LlmGateway.TaskDraft> drafts = llm.splitTasks(requirement, session.getQueryResult());
        return new PreviewResponse(drafts);
    }
}
