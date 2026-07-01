package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskDecompositionEmptyDraftTest {

    @Autowired TaskDecompositionService svc;
    @Autowired TaskMapper tasks;
    @Autowired TaskStepMapper steps;

    @MockBean LlmGateway llmGateway;

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary GitNexusClient gitNexus() {
            return new GitNexusClient() {
                public QueryResult query(String q, String r) {
                    return new QueryResult(List.of(), List.of());
                }
                public SymbolContext context(String n, String r) { return null; }
                public ImpactResult impact(String t, String d, String r) {
                    return new ImpactResult(t, d, "LOW", java.util.Map.of());
                }
                public boolean detectChanges(String r) { return true; }
            };
        }
    }

    @Test
    void emptyDraftsSetsDecomposingFailedAndNoSteps() {
        when(llmGateway.splitTasks(any(), any())).thenReturn(List.of());

        Long taskId = svc.decompose("需求查不到符号", "repo", 1L);

        var task = tasks.selectById(taskId);
        assertNotNull(task);
        assertEquals(TaskStatus.DECOMPOSING_FAILED, task.getStatus());

        var allSteps = steps.selectList(null).stream()
            .filter(s -> s.getTaskId().equals(taskId)).toList();
        assertTrue(allSteps.isEmpty(), "no steps should be created for empty drafts");
    }
}
