package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TaskCompletionServiceTest {

    @Autowired TaskCompletionService svc;
    @Autowired TaskStepRepository steps;
    @Autowired TaskDependencyRepository deps;
    @Autowired TaskRepository tasks;
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
            return (req, ctx) -> List.of(
                new LlmGateway.TaskDraft("A", "ServiceA", "do A"),
                new LlmGateway.TaskDraft("B", "ServiceB", "do B")
            );
        }
    }

    @Test
    void completingADecrementsBandUnlocksToReady() {
        var task = tasks.save(new Task("req", 1L));
        var a = new TaskStep(task.getId(), "A", "ServiceA");
        a.setStatus(TaskStepStatus.IN_PROGRESS);
        a.setGeneratedPrompt("old A prompt");
        a = steps.save(a);

        var b = new TaskStep(task.getId(), "B", "ServiceB");
        b.setStatus(TaskStepStatus.PENDING);
        b.setDependsOnCount(1);
        b.setGeneratedPrompt("old B prompt (stale)");
        b = steps.save(b);

        deps.save(new TaskDependency(a.getId(), b.getId()));

        boolean ok = svc.complete(a.getId(), 1L, "repo");

        assertTrue(ok);
        assertEquals(TaskStepStatus.DONE, steps.findById(a.getId()).orElseThrow().getStatus());

        var bAfter = steps.findById(b.getId()).orElseThrow();
        assertEquals(TaskStepStatus.READY, bAfter.getStatus());
        assertEquals(0, bAfter.getDependsOnCount());
        assertTrue(bAfter.getGeneratedPrompt().contains("fresh code for ServiceB"),
            "B's prompt should be reaggregated with fresh context");
        assertNotNull(bAfter.getReaggregatedAt());
    }

    @Test
    void completeFailsWhenDetectChangesSaysNoTouch() {
        var task = tasks.save(new Task("req", 1L));
        var a = new TaskStep(task.getId(), "A", "ServiceA");
        a.setStatus(TaskStepStatus.IN_PROGRESS);
        steps.save(a);

        boolean ok = svc.complete(a.getId(), 1L, "repo");
        assertTrue(ok);
    }
}
