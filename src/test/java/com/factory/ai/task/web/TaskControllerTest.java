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
    void listReturnsPaginatedTasks() throws Exception {
        // 先创建一个任务
        when(gitNexusClient.query(any(), any()))
            .thenReturn(new QueryResult(List.of(), List.of()));
        when(llmGateway.splitTasks(any(), any()))
            .thenReturn(List.of());
        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test req\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isOk());

        // GET /tasks?page=1&size=10 应返回分页结构
        mvc.perform(get("/tasks").param("page", "1").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.items[0].requirement").value("test req"))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.totalPages").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
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
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].targetSymbol").value("UserService"))
            .andExpect(jsonPath("$.items[1].targetSymbol").value("UserController"))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.page").value(1));
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

    // --- 输入校验测试 ---

    @Test
    void decomposeRejectsBlankRequirement() throws Exception {
        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void decomposeRejectsNullAdminId() throws Exception {
        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void decomposeRejectsNegativeAdminId() throws Exception {
        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void claimRejectsNullUserId() throws Exception {
        mvc.perform(post("/tasks/1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void completeRejectsBlankRepo() throws Exception {
        mvc.perform(post("/tasks/1/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"repo\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void executeRejectsZeroUserId() throws Exception {
        mvc.perform(post("/tasks/1/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":0,\"repo\":\"r\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
