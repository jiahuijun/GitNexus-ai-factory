package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GitNexusClient gitNexusClient;
    @MockBean LlmGateway llmGateway;

    @Test
    void claimNonexistentReturns409() throws Exception {
        mvc.perform(post("/tasks/999999/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1}"))
            .andExpect(status().isConflict());
    }

    @Test
    void completeNonexistentReturnsOkFalse() throws Exception {
        mvc.perform(post("/tasks/999999/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"repo\":\"r\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    @Test
    void decomposeReturns503WhenGitNexusDown() throws Exception {
        // TaskDecompositionService 会调 gitNexus.query() → 抛 GitNexusException
        // 但 TaskControllerTest 已 @MockBean GitNexusClient,默认 mock 不抛
        // 需要 stub:gitNexusClient.query(any(), any()) 抛 GitNexusException
        org.mockito.Mockito.doThrow(new com.factory.ai.gitnexus.GitNexusException("connection refused"))
            .when(gitNexusClient).query(any(), any());

        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("connection refused"));
    }
}
