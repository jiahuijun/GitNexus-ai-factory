package com.factory.ai.chat.web;

import com.factory.ai.chat.service.ChatClarificationService;
import com.factory.ai.chat.web.dto.DecomposeResponse;
import com.factory.ai.chat.web.dto.MessageResponse;
import com.factory.ai.chat.web.dto.StartSessionResponse;
import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.service.LlmGateway;
import com.factory.ai.task.service.TaskDecompositionService;
import com.factory.ai.task.service.TaskExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ChatController} 的 MockMvc 测试。
 *
 * <p>通过 {@code @MockBean} 替换 {@link ChatClarificationService}，
 * 验证三个端点的 HTTP 行为：
 * <ul>
 *   <li>POST /chat/sessions — 200 + JSON</li>
 *   <li>POST /chat/sessions/{id}/messages — 200 / 404</li>
 *   <li>POST /chat/sessions/{id}/decompose — 200 / 404</li>
 *   <li>上游异常 → 503</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ChatClarificationService service;
    @MockBean GitNexusClient gitNexusClient;
    @MockBean LlmGateway llmGateway;
    @MockBean TaskDecompositionService taskDecompositionService;
    @MockBean TaskExecutionService taskExecutionService;

    @Test
    void startSessionReturns200() throws Exception {
        when(service.start(any(), any(), any()))
            .thenReturn(new StartSessionResponse("session-1", "第一个问题", false));

        mvc.perform(post("/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("session-1"))
            .andExpect(jsonPath("$.firstQuestion").value("第一个问题"))
            .andExpect(jsonPath("$.ready").value(false));
    }

    @Test
    void sendMessageReturns200() throws Exception {
        when(service.sendMessage(eq("s1"), any()))
            .thenReturn(new MessageResponse("回复", true));

        mvc.perform(post("/chat/sessions/s1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reply").value("回复"))
            .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    void sendMessageReturns404WhenSessionExpired() throws Exception {
        when(service.sendMessage(eq("expired"), any()))
            .thenThrow(new java.util.NoSuchElementException("not found"));

        mvc.perform(post("/chat/sessions/expired/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hi\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void decomposeReturns200() throws Exception {
        when(service.decompose(eq("s1"), any()))
            .thenReturn(new DecomposeResponse(42L));

        mvc.perform(post("/chat/sessions/s1/decompose"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(42));
    }

    @Test
    void decomposeReturns404WhenSessionExpired() throws Exception {
        when(service.decompose(eq("expired"), any()))
            .thenThrow(new java.util.NoSuchElementException("not found"));

        mvc.perform(post("/chat/sessions/expired/decompose"))
            .andExpect(status().isNotFound());
    }

    @Test
    void startReturns503WhenGitNexusDown() throws Exception {
        when(service.start(any(), any(), any()))
            .thenThrow(new GitNexusException("connection refused"));

        mvc.perform(post("/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("connection refused"));
    }

    @Test
    void sendMessageReturns503WhenLlmDown() throws Exception {
        when(service.sendMessage(any(), any()))
            .thenThrow(new LlmException("LLM timeout"));

        mvc.perform(post("/chat/sessions/s1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hi\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }
}
