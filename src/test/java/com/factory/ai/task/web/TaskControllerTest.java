package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import com.factory.ai.task.service.LlmGateway;
import com.factory.ai.task.service.TaskExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GitNexusClient gitNexusClient;
    @MockBean LlmGateway llmGateway;
    @MockBean TaskExecutionService taskExecutionService;

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
        doThrow(new com.factory.ai.gitnexus.GitNexusException("connection refused"))
            .when(gitNexusClient).query(any(), any());

        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("connection refused"));
    }

    @Test
    void listReturnsAllTasks() throws Exception {
        // 先创建一个任务
        when(gitNexusClient.query(any(), any()))
            .thenReturn(new QueryResult(List.of(), List.of()));
        when(llmGateway.splitTasks(any(), any()))
            .thenReturn(List.of());
        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test req\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isOk());

        // GET /tasks 应返回至少 1 条
        mvc.perform(get("/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$[0].requirement").value("test req"));
    }

    @Test
    void getTaskReturns404WhenNotFound() throws Exception {
        mvc.perform(get("/tasks/999999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getStepsReturns404WhenTaskNotFound() throws Exception {
        mvc.perform(get("/tasks/999999/steps"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getStepsReturnsStepsForTask() throws Exception {
        // decompose 生成 2 个步骤
        when(gitNexusClient.query(any(), any()))
            .thenReturn(new QueryResult(List.of(
                new SymbolRef("u1", "UserService", "src/UserService.java", 1, 100),
                new SymbolRef("u2", "UserController", "src/UserController.java", 1, 50)
            ), List.of()));
        when(gitNexusClient.context(any(), any()))
            .thenReturn(new com.factory.ai.gitnexus.dto.SymbolContext(
                "uid", "X", "Class", "src/X.java", 1, 10, "code", List.of(), List.of()));
        when(gitNexusClient.impact(any(), any(), any()))
            .thenReturn(new com.factory.ai.gitnexus.dto.ImpactResult("X", "upstream", "LOW", java.util.Map.of()));
        when(gitNexusClient.detectChanges(any())).thenReturn(true);
        when(llmGateway.splitTasks(any(), any()))
            .thenReturn(List.of(
                new LlmGateway.TaskDraft("加方法A", "UserService", "design A"),
                new LlmGateway.TaskDraft("加接口B", "UserController", "design B")
            ));

        String taskId = mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/tasks/" + taskId + "/steps"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].targetSymbol").value("UserService"))
            .andExpect(jsonPath("$[1].targetSymbol").value("UserController"));
    }

    @Test
    void getDependenciesReturns404WhenTaskNotFound() throws Exception {
        mvc.perform(get("/tasks/999999/dependencies"))
            .andExpect(status().isNotFound());
    }

    @Test
    void executeNonexistentReturnsOkFalse() throws Exception {
        when(taskExecutionService.execute(anyLong(), anyLong(), any()))
            .thenThrow(new java.util.NoSuchElementException("not found"));

        mvc.perform(post("/tasks/999999/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"repo\":\"r\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    @Test
    void executeReturnsTrueWhenSuccessful() throws Exception {
        when(taskExecutionService.execute(anyLong(), anyLong(), any()))
            .thenReturn(true);

        mvc.perform(post("/tasks/1/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"repo\":\"r\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }
}
