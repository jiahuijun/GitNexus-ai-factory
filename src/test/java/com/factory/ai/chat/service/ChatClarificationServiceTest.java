package com.factory.ai.chat.service;

import com.factory.ai.chat.session.ChatMessage;
import com.factory.ai.chat.session.ChatSession;
import com.factory.ai.chat.session.ChatSessionStore;
import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import com.factory.ai.task.service.LlmGateway;
import com.factory.ai.task.service.TaskDecompositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ChatClarificationService} 的单元测试。
 *
 * <p>使用 Mockito mock GitNexus / Llm / Store / DecompService，
 * 验证三个编排方法的行为：
 * <ul>
 *   <li>{@code start} — 调 GitNexus query 一次 + LLM clarify 一次</li>
 *   <li>{@code sendMessage} — 不调 GitNexus，调 LLM clarify</li>
 *   <li>{@code decompose} — 用 refinedRequirement 调 decompService.decompose()</li>
 * </ul>
 */
class ChatClarificationServiceTest {

    GitNexusClient gitNexus;
    LlmGateway llm;
    ChatSessionStore store;
    TaskDecompositionService decompService;
    ChatClarificationService service;

    @BeforeEach
    void setUp() {
        gitNexus = mock(GitNexusClient.class);
        llm = mock(LlmGateway.class);
        store = mock(ChatSessionStore.class);
        decompService = mock(TaskDecompositionService.class);
        service = new ChatClarificationService(gitNexus, llm, store, decompService);
    }

    @Test
    void startCallsGitNexusOnceAndLlmClarify() {
        // Arrange
        var queryResult = new QueryResult(List.of(
            new SymbolRef("u1", "BinaryLogClient", "src/BinaryLogClient.java", 1, 100)
        ), List.of());
        when(gitNexus.query(any(), any())).thenReturn(queryResult);
        when(store.create(any(), any(), any(), any())).thenAnswer(inv -> {
            ChatSession s = new ChatSession("session-1", inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3));
            return s;
        });
        var reply = new LlmGateway.ClarifyReply("你想要指数退避还是固定间隔？", false, null);
        when(llm.clarify(any(), any(), anyList())).thenReturn(reply);

        // Act
        var resp = service.start("加心跳检测", "repo", 1L);

        // Assert
        verify(gitNexus, times(1)).query("加心跳检测", "repo");
        verify(llm, times(1)).clarify(eq("加心跳检测"), eq(queryResult), eq(List.of()));
        verify(store, times(1)).create("加心跳检测", "repo", 1L, queryResult);
        verify(store, times(1)).save(any());
        assertEquals("session-1", resp.sessionId());
        assertEquals("你想要指数退避还是固定间隔？", resp.firstQuestion());
        assertFalse(resp.ready());
    }

    @Test
    void sendMessageDoesNotCallGitNexusButCallsClarify() {
        // Arrange
        var queryResult = new QueryResult(List.of(), List.of());
        ChatSession session = new ChatSession("s1", "原始需求", "repo", 1L, queryResult);
        session.getHistory().add(new ChatMessage("assistant", "第一个问题"));
        when(store.get("s1")).thenReturn(session);
        var reply = new LlmGateway.ClarifyReply("明白了，用指数退避", true, "精炼需求：加心跳检测，指数退避");
        when(llm.clarify(any(), any(), anyList())).thenReturn(reply);

        // Act
        var resp = service.sendMessage("s1", "用指数退避");

        // Assert
        verify(gitNexus, never()).query(any(), any());
        verify(llm, times(1)).clarify(eq("原始需求"), eq(queryResult), anyList());
        verify(store, times(1)).save(session);
        assertEquals("明白了，用指数退避", resp.reply());
        assertTrue(resp.ready());
        assertEquals("精炼需求：加心跳检测，指数退避", session.getRefinedRequirement());
    }

    @Test
    void decomposeUsesRefinedRequirement() {
        // Arrange
        var queryResult = new QueryResult(List.of(), List.of());
        ChatSession session = new ChatSession("s1", "原始需求", "repo", 1L, queryResult);
        session.setRefinedRequirement("精炼需求：加心跳检测，指数退避");
        when(store.get("s1")).thenReturn(session);
        when(decompService.decompose(any(), any(), any())).thenReturn(42L);

        // Act
        var resp = service.decompose("s1");

        // Assert
        verify(decompService, times(1)).decompose("精炼需求：加心跳检测，指数退避", "repo", 1L);
        assertEquals(42L, resp.taskId());
        assertEquals("DECOMPOSED", session.getState());
        assertEquals(42L, session.getTaskId());
    }

    @Test
    void decomposeFallsBackToOriginalRequirementWhenNoRefined() {
        // Arrange
        var queryResult = new QueryResult(List.of(), List.of());
        ChatSession session = new ChatSession("s1", "原始需求", "repo", 1L, queryResult);
        // 不设置 refinedRequirement
        when(store.get("s1")).thenReturn(session);
        when(decompService.decompose(any(), any(), any())).thenReturn(7L);

        // Act
        var resp = service.decompose("s1");

        // Assert
        verify(decompService, times(1)).decompose("原始需求", "repo", 1L);
        assertEquals(7L, resp.taskId());
    }

    @Test
    void sendMessageThrowsWhenSessionExpired() {
        when(store.get("expired")).thenReturn(null);
        assertThrows(java.util.NoSuchElementException.class, () -> service.sendMessage("expired", "hello"));
    }

    @Test
    void decomposeThrowsWhenSessionExpired() {
        when(store.get("expired")).thenReturn(null);
        assertThrows(java.util.NoSuchElementException.class, () -> service.decompose("expired"));
    }
}
