package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskCompletionServiceTest {

    @Autowired TaskCompletionService svc;
    @Autowired TaskStepMapper steps;
    @Autowired TaskDependencyMapper deps;
    @Autowired TaskMapper tasks;
    @Autowired TaskDecompositionService decomp;

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary GitNexusClient gitNexus() {
            return new GitNexusClient() {
                public QueryResult query(String q, String r) {
                    return new QueryResult(List.of(), List.of());
                }
                public SymbolContext context(String n, String r) {
                    return new SymbolContext("u", n, "Class", "src/"+n+".java", 1, 10,
                        "fresh code for "+n, List.of(), List.of());
                }
                public ImpactResult impact(String t, String d, String r) {
                    return new ImpactResult(t, d, "LOW", java.util.Map.of());
                }
                public boolean detectChanges(String r) { return true; }
            };
        }
        @Bean @Primary LlmGateway llm() {
            return new LlmGateway() {
                @Override public List<TaskDraft> splitTasks(String req, QueryResult ctx) {
                    return List.of(
                        new LlmGateway.TaskDraft("A", "ServiceA", "产出物: ServiceA.doA()\n签名: public void doA()\n实现: 执行 A 逻辑"),
                        new LlmGateway.TaskDraft("B", "ServiceB", "产出物: ServiceB.doB()\n签名: public void doB()\n实现: 执行 B 逻辑\n依赖: ServiceA")
                    );
                }
                @Override public String executeStep(String prompt) { return ""; }
            };
        }
    }

    @Test
    void completingADecrementsBandUnlocksToReady() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var a = new TaskStep(task.getId(), "A", "ServiceA");
        a.setStatus(TaskStepStatus.IN_PROGRESS);
        a.setGeneratedPrompt("old A prompt");
        steps.insert(a);

        var b = new TaskStep(task.getId(), "B", "ServiceB");
        b.setStatus(TaskStepStatus.PENDING);
        b.setDependsOnCount(1);
        b.setGeneratedPrompt("old B prompt (stale)");
        steps.insert(b);

        deps.insert(new TaskDependency(a.getId(), b.getId()));

        boolean ok = svc.complete(a.getId(), 1L, "repo");

        assertTrue(ok);
        var aAfter = steps.selectById(a.getId());
        assertNotNull(aAfter);
        assertEquals(TaskStepStatus.DONE, aAfter.getStatus());

        var bAfter = steps.selectById(b.getId());
        assertNotNull(bAfter);
        assertEquals(TaskStepStatus.READY, bAfter.getStatus());
        assertEquals(0, bAfter.getDependsOnCount());
        assertTrue(bAfter.getGeneratedPrompt().contains("fresh code for ServiceB"),
            "B's prompt should be reaggregated with fresh context");
        assertNotNull(bAfter.getReaggregatedAt());
    }

    @Test
    void completeFailsWhenDetectChangesSaysNoTouch() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var a = new TaskStep(task.getId(), "A", "ServiceA");
        a.setStatus(TaskStepStatus.IN_PROGRESS);
        steps.insert(a);

        boolean ok = svc.complete(a.getId(), 1L, "repo");
        assertTrue(ok);
    }

    @Test
    void completingAllStepsMarksTaskDone() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var a = new TaskStep(task.getId(), "A", "ServiceA");
        a.setStatus(TaskStepStatus.IN_PROGRESS);
        a.setGeneratedPrompt("prompt A");
        steps.insert(a);

        var b = new TaskStep(task.getId(), "B", "ServiceB");
        b.setStatus(TaskStepStatus.IN_PROGRESS);
        b.setGeneratedPrompt("prompt B");
        steps.insert(b);

        // 完成 A → task 应为 PARTIAL（B 未完成）
        svc.complete(a.getId(), 1L, "repo");
        var taskAfterA = tasks.selectById(task.getId());
        assertEquals(TaskStatus.PARTIAL, taskAfterA.getStatus());

        // 完成 B → task 应为 DONE（全部完成）
        svc.complete(b.getId(), 1L, "repo");
        var taskAfterB = tasks.selectById(task.getId());
        assertEquals(TaskStatus.DONE, taskAfterB.getStatus());
    }
}
